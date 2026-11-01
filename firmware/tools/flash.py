#!/usr/bin/env python3
"""Flash the Return Machine with retries.

The ESP32-S3's USB serial port often locks up after a flash and reset: every esptool call then
fails with "Write timeout" until the cable is re-plugged. This wrapper

  1. tries up to --attempts rounds, changing the reset mode and baud rate each round,
  2. stops at once when retrying cannot help (port held by a serial monitor, files missing),
  3. if every round fails, waits for the cable to be unplugged and plugged back in, and flashes
     the moment the port returns (--replug-timeout seconds, 0 to skip).

Usage (from any folder):
    python D:/TOMS/firmware/tools/flash.py phase1        bootloader + signed app (OTA phase 1)
    python D:/TOMS/firmware/tools/flash.py plain         the single-image build, at 0x0
    python D:/TOMS/firmware/tools/flash.py --image 0x0=a.bin --image 0x20000=b.bin

The chip has 4 MB flash, so --flash_size is always 4MB. Never flash `plain` over a phase 1
layout, or the bootloader is overwritten.
"""
import argparse
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import board  # noqa: E402
from board import say  # noqa: E402

FW = Path(__file__).resolve().parent.parent
ESPTOOL = Path("D:/zephyrproject/modules/hal/espressif/tools/esptool_py/esptool.py")
FLASH_END = 4 * 1024 * 1024

TARGETS = {
    "phase1": [
        (0x0, FW / "build/phase1/mcuboot/zephyr/zephyr.bin"),
        (0x20000, FW / "build/phase1/return-machine/zephyr/zephyr.signed.confirmed.bin"),
    ],
    "plain": [(0x0, FW / "build/devkitc/zephyr/zephyr.bin")],
}

# Each round uses a different way of getting the chip into its flasher. usb_reset is the one
# that has worked after a re-plug; no_reset works when the chip is already in the flasher stub.
ROUNDS = [
    ("usb_reset", 230400),
    ("default_reset", 115200),
    ("no_reset", 115200),
    ("usb_reset", 115200),
    ("default_reset", 230400),
]


def classify(out):
    """Return (kind, advice). kind 'fatal' means retrying cannot help."""
    low = out.lower()
    if "access is denied" in low or "permissionerror" in low or "port is busy" in low:
        return "fatal", "COM port is held by another program. Close the serial monitor and run again."
    if "could not open" in low or "filenotfounderror" in low or "doesn't exist" in low:
        return "gone", "COM port not found: the cable is out or the board re-enumerated."
    if "write timeout" in low:
        return "locked", "Write timeout: the USB serial port is locked up (needs a re-plug)."
    if "guru meditation" in low:
        return "locked", "esptool saw the chip crash while resetting it (also cleared by a re-plug)."
    if "md5" in low and "does not match" in low:
        return "flaky", "Written data did not verify (flaky USB link)."
    if "failed to connect" in low or "no serial data received" in low:
        return "flaky", "Could not reach the chip's flasher."
    return "unknown", "Unrecognised failure (see output above)."


def run_once(port, mode, baud, images):
    cmd = [
        sys.executable, str(ESPTOOL), "--chip", "esp32s3", "--port", port,
        "--baud", str(baud), "--before", mode, "--after", "hard_reset",
        "write_flash", "-u", "--flash_mode", "dio", "--flash_freq", "80m", "--flash_size", "4MB",
    ]
    for addr, path in images:
        cmd += [hex(addr), str(path)]
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
        out = (r.stdout or "") + (r.stderr or "")
        ok = r.returncode == 0 and "Hash of data verified" in out
    except subprocess.TimeoutExpired as e:
        out = ((e.stdout or b"").decode(errors="replace") if isinstance(e.stdout, bytes) else (e.stdout or ""))
        out += "\nTIMEOUT after 300 s"
        ok = False
    tail = [ln for ln in out.splitlines() if ln.strip() and not re.match(r"Writing at", ln)]
    return ok, out, tail[-6:]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("target", nargs="?", choices=sorted(TARGETS), help="which set of images")
    ap.add_argument("--image", action="append", default=[], metavar="ADDR=FILE")
    ap.add_argument("--port", default="COM11")
    ap.add_argument("--attempts", type=int, default=5)
    ap.add_argument("--replug-timeout", type=float, default=120.0)
    a = ap.parse_args()

    images = list(TARGETS[a.target]) if a.target else []
    for spec in a.image:
        addr, _, f = spec.partition("=")
        images.append((int(addr, 0), Path(f)))
    if not images:
        ap.error("give a target or at least one --image")

    for addr, path in images:
        if not path.exists():
            sys.exit(f"missing image: {path}\n(build it first)")
        if addr + path.stat().st_size > FLASH_END:
            sys.exit(f"{path.name} at 0x{addr:x} runs past the 4 MB flash")
    say(f"board: {board.describe()}")
    say("images to write:")
    for addr, path in images:
        say(f"  0x{addr:06x}  {path.stat().st_size:>8} B  {path}")
    cur = board.find_board()
    if cur and cur != a.port:
        say(f"NOTE: the board is on {cur}, not {a.port}; using {cur}")
        a.port = cur

    def attempt(label, mode, baud):
        port = board.find_board() or a.port
        say("")
        say(f"[{label}] flashing on {port}: --before {mode}, {baud} baud (about 30-60 s) ...")
        t0 = time.time()
        ok, out, tail = run_once(port, mode, baud, images)
        took = time.time() - t0
        if ok:
            say(f"  OK after {took:.0f}s: every image written and its hash verified, chip reset.")
            return True, None
        kind, advice = classify(out)
        say(f"  failed after {took:.0f}s [{kind}]: {advice}")
        for ln in tail[-4:]:
            say("    esptool| " + ln[:150])
        return False, kind

    last_kind = None
    for n in range(1, a.attempts + 1):
        mode, baud = ROUNDS[(n - 1) % len(ROUNDS)]
        ok, last_kind = attempt(f"round {n}/{a.attempts}", mode, baud)
        if ok:
            return 0
        if last_kind == "fatal":
            return 2
        if n < a.attempts:
            say("  next round in 3 s ...")
            time.sleep(3)

    if a.replug_timeout > 0 and last_kind in ("locked", "gone", "flaky", "unknown"):
        say("")
        say("every round failed with a USB problem, so a re-plug is needed. >>> UNPLUG the cable and "
            "PLUG IT BACK IN now <<<")
        newport = board.wait_replug(a.replug_timeout)
        if newport:
            a.port = newport
            ok, _ = attempt("after re-plug", "usb_reset", 230400)
            if ok:
                return 0
        else:
            say("No re-plug was seen in time.")
    say("")
    say(f"FLASH FAILED after {a.attempts} rounds. Unplug and re-plug the cable and run it again,"
        " or hold BOOT while plugging in for download mode.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
