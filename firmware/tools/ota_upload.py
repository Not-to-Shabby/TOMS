#!/usr/bin/env python3
"""Upload a signed image to the Return Machine over Bluetooth (OTA_PLAN.md phase 4).

This is a stand-in for the phone app: it speaks the same ota_control (0x0C) / ota_data (0x0D)
protocol as lib/toms_core/include/toms/ota.h. Run it from the PC next to the board.

    python D:/TOMS/firmware/tools/ota_upload.py                  upload good-2 (the normal case)
    python D:/TOMS/firmware/tools/ota_upload.py --corrupt        flip one byte on the way: the
                                                                 machine must REFUSE it (hash)
    python D:/TOMS/firmware/tools/ota_upload.py --drop-at 150000 drop the link after 150000 bytes,
                                                                 reconnect, and resume
    python D:/TOMS/firmware/tools/ota_upload.py --image PATH     another .signed.bin

Needs the running image to be CONFIRMED and OLDER than the uploaded one (good-1 running, good-2
uploaded). The link must be bonded: the script pairs first (Windows may pop up a pairing prompt).
It prints every step with a timestamp and every notification the machine sends.
"""
import argparse
import asyncio
import hashlib
import struct
import sys
import time
from pathlib import Path

from bleak import BleakClient, BleakScanner

sys.path.insert(0, str(Path(__file__).resolve().parent))
from board import say  # noqa: E402

BASE = "544f4d53-5245-5455-524e-414348494e{:02x}"
CTRL, DATA, INFO = BASE.format(0x0C), BASE.format(0x0D), BASE.format(0x09)
SVC = BASE.format(0x45)
DEFAULT_IMAGE = Path("D:/TOMS/firmware/build/phase2/images/good-2.signed.bin")
CHUNK = 58  # TOMS_OTA_CHUNK_MAX

OP = {1: "START", 2: "FINISH", 3: "ABORT", 4: "STATUS"}
STATUS = {0: "OK", 1: "BAD_MSG", 2: "BUSY", 3: "STATE", 4: "SIZE", 5: "OFFSET", 6: "HASH",
          7: "HEADER", 8: "VERSION", 9: "SECURITY", 10: "FLASH", 11: "BATTERY"}
STATE = {0: "idle", 1: "receiving", 2: "verifying", 3: "ready", 4: "failed"}


def parse_reply(b):
    """Decode a 12-byte ota_control notification into a dict."""
    if len(b) != 12:
        return {"raw": b.hex(), "kind": "?"}
    op, st, off, state, mj, mn, rev, flags = struct.unpack("<BBIBBBHB", bytes(b))
    kind = {0xA0: "progress", 0xB0: "resync", 0xF0: "failed"}.get(op)
    if kind is None and op & 0x80:
        kind = "reply:" + OP.get(op & 0x7F, hex(op & 0x7F))
    return {"kind": kind or hex(op), "status": STATUS.get(st, st), "offset": off,
            "state": STATE.get(state, state), "running": f"{mj}.{mn}.{rev}",
            "confirmed": bool(flags & 1)}


def chunk_frame(offset, payload):
    return struct.pack("<I", offset) + payload


def start_frame(size, sha):
    return struct.pack("<BI", 1, size) + sha


class Session:
    def __init__(self, image, corrupt, drop_at, pace):
        self.data = image
        self.sha = hashlib.sha256(image).digest()
        self.corrupt, self.drop_at, self.pace = corrupt, drop_at, pace
        self.events = asyncio.Queue()
        self.want = None  # offset the machine asked for in a resync
        self.client = None
        self.dropped = False

    def on_notify(self, _h, b):
        r = parse_reply(b)
        if r["kind"] == "progress":
            say(f"  machine has {r['offset']} bytes")
        else:
            say(f"  <- {r['kind']}: status {r['status']}, offset {r['offset']}, state {r['state']}, "
                f"running {r['running']} ({'confirmed' if r['confirmed'] else 'ON TRIAL'})")
        if r["kind"] == "resync":
            self.want = r["offset"]
        self.events.put_nowait(r)

    async def wait_for(self, kind, timeout):
        end = time.time() + timeout
        while time.time() < end:
            try:
                r = await asyncio.wait_for(self.events.get(), max(0.05, end - time.time()))
            except asyncio.TimeoutError:
                break
            if r["kind"] == kind or r["kind"] == "failed":
                return r
        return None

    async def connect(self):
        say("scanning for a TOMS-RM board (10 s) ...")
        dev = await BleakScanner.find_device_by_filter(
            lambda d, ad: SVC in [u.lower() for u in (ad.service_uuids or [])], timeout=10)
        if dev is None:
            sys.exit("no board advertising the TOMS service was found")
        say(f"found {dev.name} {dev.address}; connecting")
        self.client = BleakClient(dev)
        await self.client.connect()
        say("connected; pairing (bonding) ...")
        try:
            await self.client.pair()
        except Exception as e:  # already bonded is fine
            say(f"  pair() said: {type(e).__name__}: {e} (continuing; the machine checks security)")
        info = await self.client.read_gatt_char(INFO)
        say(f"device_info: {bytes(info).hex()}")
        await self.client.start_notify(CTRL, self.on_notify)
        say("subscribed to ota_control")

    async def announce(self):
        say(f"START: {len(self.data)} bytes, sha256 {self.sha.hex()[:16]}...")
        await self.client.write_gatt_char(CTRL, start_frame(len(self.data), self.sha), response=True)
        r = await self.wait_for("reply:START", 90)
        if r is None or r["status"] != "OK":
            sys.exit(f"START refused or no reply: {r}")
        return r["offset"]

    async def stream(self, offset):
        total = len(self.data)
        t0, sent0, last = time.time(), offset, time.time()
        while offset < total:
            if self.want is not None:
                say(f"  resync: machine wants {self.want}, going back from {offset}")
                offset, self.want = self.want, None
            if self.drop_at and not self.dropped and offset >= self.drop_at:
                self.dropped = True
                say(f"DROPPING the link at {offset} bytes (test) ...")
                await self.client.disconnect()
                await asyncio.sleep(3)
                await self.connect()
                offset = await self.announce()
                say(f"resumed: machine says continue at {offset}")
                continue
            payload = self.data[offset:offset + CHUNK]
            if self.corrupt and offset <= 100000 < offset + len(payload):
                b = bytearray(payload)
                b[100000 - offset] ^= 0xFF
                payload = bytes(b)
                say("  corrupted one byte at 100000 on the way")
            await self.client.write_gatt_char(DATA, chunk_frame(offset, payload), response=False)
            offset += len(payload)
            if self.pace:
                await asyncio.sleep(self.pace)
            if time.time() - last > 5:
                last = time.time()
                rate = (offset - sent0) / max(1e-6, time.time() - t0) / 1024
                say(f"  sent {offset}/{total} ({100 * offset // total}%), {rate:.1f} KB/s")
        say(f"all {total} bytes sent in {time.time() - t0:.0f} s")

    async def run(self):
        await self.connect()
        offset = await self.announce()
        await self.stream(offset)
        await asyncio.sleep(1)
        say("FINISH: asking the machine to check the image and request the swap")
        await self.client.write_gatt_char(CTRL, bytes([2]), response=True)
        r = await self.wait_for("reply:FINISH", 60)
        if r is None:
            say("RESULT: no reply to FINISH")
            return 1
        ok = r["status"] == "OK"
        say(f"RESULT: FINISH answered {r['status']}" + (" - the machine reboots into the trial image now"
                                                        if ok else " - the machine REFUSED the image"))
        return 0 if ok else 2


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", type=Path, default=DEFAULT_IMAGE)
    ap.add_argument("--corrupt", action="store_true", help="flip a byte in transit (must be refused)")
    ap.add_argument("--drop-at", type=int, default=0, help="drop the link after N bytes and resume")
    ap.add_argument("--pace", type=float, default=0.0, help="seconds to wait between writes")
    a = ap.parse_args()
    if not a.image.exists():
        sys.exit(f"missing {a.image}: run build_phase2.sh first")
    data = a.image.read_bytes()
    say(f"image {a.image.name}: {len(data)} bytes")
    return asyncio.run(Session(data, a.corrupt, a.drop_at, a.pace).run())


if __name__ == "__main__":
    sys.exit(main())
