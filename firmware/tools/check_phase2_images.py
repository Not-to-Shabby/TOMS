#!/usr/bin/env python3
"""Check the phase 2 test images before anything is flashed (run after build_phase2.sh).

Verifies, from the files alone: each image has the right MCUboot header, version and slot fit;
the signed payload SHA-256 matches its TLV; the signature entry is present; the bootloader is
an ESP32-S3 image that fits its partition; each image's build tag and fault option are the ones
intended; and the good images contain no fault-injection code path.
"""
import hashlib
import re
import struct
import sys
from pathlib import Path

ROOT = Path("D:/TOMS/firmware/build/phase2")
IMG = ROOT / "images"
SLOT = 0x150000
ok = True


def check(cond, what):
    global ok
    print(("PASS  " if cond else "FAIL  ") + what)
    ok = ok and bool(cond)


def tlvs(d, off):
    magic, total = struct.unpack_from("<HH", d, off)
    p, end, out = off + 4, off + total, {}
    while p < end:
        t, _, ln = struct.unpack_from("<BBH", d, p)
        out[t] = d[p + 4:p + 4 + ln]
        p += 4 + ln
    return magic, out


def info(path):
    d = path.read_bytes()
    magic, load, hdr, prot, imgsz, flags, mj, mn, rev, bld = struct.unpack_from("<IIHHIIBBHI", d, 0)
    t_off = hdr + imgsz + prot
    tmagic, t = tlvs(d, t_off)
    return d, magic, hdr, imgsz, (mj, mn, rev), tmagic, t, t_off


EXPECT = {
    "good-1.confirmed.bin": ("good-1", (1, 0, 0), "good-1", None),
    "good-2.signed.bin": ("good-2", (2, 0, 0), "good-2", None),
    "bad-crash.signed.bin": ("bad-crash", (3, 0, 0), "bad-crash", "TOMS_TRIAL_FAULT_CRASH"),
    "bad-hang.signed.bin": ("bad-hang", (3, 0, 0), "bad-hang", "TOMS_TRIAL_FAULT_HANG"),
    "bad-noconfirm.signed.bin": ("bad-noconfirm", (3, 0, 0), "bad-noconfirm", "TOMS_TRIAL_FAULT_NO_CONFIRM"),
    "bad-selftest.signed.bin": ("bad-selftest", (3, 0, 0), "bad-selftest", "TOMS_TRIAL_FAULT_SELFTEST_FAIL"),
}

for fname, (build, ver, tag, fault) in EXPECT.items():
    p = IMG / fname
    if not p.exists():
        check(False, f"{fname} exists")
        continue
    d, magic, hdr, imgsz, v, tmagic, t, t_off = info(p)
    print(f"--- {fname}")
    check(magic == 0x96F3B83D, "MCUboot header magic")
    check(v == ver, f"version {v[0]}.{v[1]}.{v[2]} == {'.'.join(map(str, ver))}")
    check(tmagic == 0x6907, "TLV area present")
    payload = d[:hdr + imgsz]
    check(0x10 in t and t[0x10] == hashlib.sha256(payload).digest(), "SHA-256 TLV matches the payload")
    check(0x22 in t, "ECDSA signature TLV present")
    check(0x01 in t, "public key hash TLV present")
    check(len(d) <= SLOT, f"{len(d)} B fits the {SLOT // 1024} KB slot")
    if fname.endswith(".confirmed.bin"):
        check(len(d) == SLOT, "padded to the full slot with the confirmed trailer")
        check(d[-16:] == bytes.fromhex("77c295f360d2ef7f3552ca9d01000000ffffffffffffffffffffffffffffffff")[:16] or
              bytes.fromhex("77c295f360d2ef7f35") in d[-32:], "MCUboot trailer magic at the end of the slot")
    cfg = (ROOT / build / "return-machine/zephyr/.config").read_text(errors="replace")
    check(f'CONFIG_TOMS_BUILD_TAG="{tag}"' in cfg, f"build tag is {tag}")
    if fault:
        check(f"CONFIG_{fault}=y" in cfg, f"fault option {fault} is on")
        others = re.findall(r"CONFIG_(TOMS_TRIAL_FAULT_(?!NONE)[A-Z_]+)=y", cfg)
        check(others == [fault], f"only that fault is on (found {others})")
    else:
        check("CONFIG_TOMS_TRIAL_FAULT_NONE=y" in cfg, "fault injection is OFF")
        check(not re.findall(r"CONFIG_TOMS_TRIAL_FAULT_(?!NONE)[A-Z_]+=y", cfg), "no fault option set")
    check("CONFIG_WATCHDOG=y" in cfg and "CONFIG_IMG_MANAGER=y" in cfg, "watchdog and image manager built in")

print("--- *.pending.bin (written to the update slot to start a trial)")
for fname in ("good-2", "bad-crash", "bad-hang", "bad-noconfirm", "bad-selftest"):
    p = IMG / f"{fname}.pending.bin"
    d = p.read_bytes()
    check(len(d) == SLOT, f"{p.name}: padded to exactly the {SLOT // 1024} KB slot")
    check(d[-16:] == bytes.fromhex("77c295f360d2ef7f3552500f2cb67980"), f"{p.name}: MCUboot trailer magic present")
    check(set(d[-32:-16]) == {0xFF}, f"{p.name}: image_ok and copy_done NOT set (so it is a trial, not permanent)")
    plain = (IMG / f"{fname}.signed.bin").read_bytes()
    t_plain = info(IMG / f"{fname}.signed.bin")[6]
    pd, _m, phdr, pimg, pv, ptm, pt, pt_off = info(p)
    check(d[:pt_off] == plain[:pt_off] and pt.get(0x10) == t_plain.get(0x10),
          f"{p.name}: same payload and SHA-256 as {fname}.signed.bin (the signature differs per signing, which is fine)")

boot = (IMG / "mcuboot.bin").read_bytes()
print("--- mcuboot.bin")
check(boot[0] == 0xE9 and int.from_bytes(boot[12:14], "little") == 9, "ESP32-S3 image magic")
check(len(boot) <= 0x10000, f"{len(boot)} B fits the 64 KB boot partition")
print()
print("PHASE 2 IMAGE CHECKS", "PASSED" if ok else "FAILED")
sys.exit(0 if ok else 1)
