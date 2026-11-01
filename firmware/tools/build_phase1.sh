#!/usr/bin/env bash
# OTA phase 1 (firmware/OTA_PLAN.md): bootloader + app with MCUboot swap-using-scratch,
# ECDSA P-256 signing, in firmware/build/phase1. Does not touch firmware/build/devkitc.
#
# After the build it checks the things that can be checked without the board:
#   - both images exist and the app is wrapped for MCUboot (magic 0x96f3b83d)
#   - the bootloader fits its partition and starts with the ESP image magic 0xE9
#   - the swap mode is the one we asked for, in both the bootloader and the app
#   - the app's partitions and the bootloader's flash map are identical
#   - the app fits its slot with room for the MCUboot trailer
#
# The development signing key shipped with MCUboot (root-ec-p256.pem) is public. Bench use
# only. A production build needs its own key, kept offline (OTA_PLAN.md, Signing).
set -euo pipefail
export PATH="/d/esp_tool/tools/cmake/3.30.2/bin:/d/esp_tool/tools/ninja/1.12.1:/d/toms_tools/dtc_pkg/mingw64/bin:/d/zephyr-sdk-0.17.2/xtensa-espressif_esp32s3_zephyr-elf/bin:$PATH"

BUILD=/d/TOMS/firmware/build/phase1
APP=/d/TOMS/firmware/return-machine

cd /d/zephyrproject
python -m west build -p always -b esp32s3_devkitc/esp32s3/procpu "$APP" \
	--build-dir "$BUILD" --sysbuild -- \
	-DSB_CONFIG_MCUBOOT_MODE_SWAP_SCRATCH=y \
	-DSB_CONFIG_BOOT_SIGNATURE_TYPE_ECDSA_P256=y

python - "$BUILD" <<'EOF'
import re, struct, subprocess, sys

build = sys.argv[1]
ok = True


def check(cond, what):
    global ok
    print(("PASS  " if cond else "FAIL  ") + what)
    ok = ok and cond


def read(p):
    return open(p, "rb").read()


def cfg(p):
    return open(p, encoding="utf-8", errors="replace").read()


boot = read(f"{build}/mcuboot/zephyr/zephyr.bin")
signed = read(f"{build}/return-machine/zephyr/zephyr.signed.bin")

check(boot[0] == 0xE9, "bootloader starts with the ESP image magic 0xE9")
check(int.from_bytes(boot[12:14], "little") == 9, "bootloader is built for the ESP32-S3")
check(len(boot) <= 64 * 1024, f"bootloader {len(boot)} B fits the 64 KB boot partition")
check(signed[:4] == bytes.fromhex("3db8f396"), "app image carries the MCUboot header")

mc = cfg(f"{build}/mcuboot/zephyr/.config")
ac = cfg(f"{build}/return-machine/zephyr/.config")
check("CONFIG_BOOT_SWAP_USING_SCRATCH=y" in mc, "bootloader mode is swap using scratch")
check("CONFIG_MCUBOOT_BOOTLOADER_MODE_SWAP_SCRATCH=y" in ac, "app is built for swap using scratch")
check("CONFIG_BOOT_SIGNATURE_TYPE_ECDSA_P256=y" in mc, "bootloader verifies ECDSA P-256")

# Flash map: parse the bootloader's compiled default_flash_map, compare to the app's DT.
nm = subprocess.run(["xtensa-espressif_esp32s3_zephyr-elf-nm", "-S",
                     f"{build}/mcuboot/zephyr/zephyr.elf"], capture_output=True, text=True).stdout
m = re.search(r"([0-9a-f]+) ([0-9a-f]+) \w default_flash_map", nm)
addr, size = int(m.group(1), 16), int(m.group(2), 16)
dump = subprocess.run(["xtensa-espressif_esp32s3_zephyr-elf-objdump", "-s", "-j", ".dram0.data",
                       f"{build}/mcuboot/zephyr/zephyr.elf"], capture_output=True, text=True).stdout
mem = {}
for line in dump.splitlines():
    mm = re.match(r"\s([0-9a-f]+) ((?:[0-9a-f]+ ?)+)", line)
    if mm:
        a = int(mm.group(1), 16)
        h = mm.group(2).replace(" ", "")
        for i in range(len(h) // 2):
            mem[a + i] = int(h[2 * i:2 * i + 2], 16)
raw = bytes(mem.get(addr + i, 0) for i in range(size))
bl_map = [struct.unpack_from("<BBHII", raw, i * 16) for i in range(size // 16)]
bl_areas = {fid: (off, sz) for fid, _dev, _pad, off, sz in bl_map}

dts = cfg(f"{build}/return-machine/zephyr/zephyr.dts").replace("\r\n", "\n")
app_areas = {}
for _lbl, body in re.findall(r"(\w+): partition@[0-9a-f]+ \{(.*?)\n\t+\};", dts, re.S):
    lab = re.search(r'label = "([^"]+)"', body)
    reg = re.search(r"reg = <\s*(0x[0-9a-f]+)\s+(0x[0-9a-f]+)\s*>", body)
    if lab and reg:
        app_areas[lab.group(1)] = (int(reg.group(1), 16), int(reg.group(2), 16))

pairs = {2: "image-0", 3: "image-1", 9: "image-scratch", 8: "storage", 0: "mcuboot"}
for fid, name in pairs.items():
    check(bl_areas.get(fid) == app_areas.get(name),
          f"{name}: bootloader {tuple(hex(x) for x in bl_areas.get(fid, (0, 0)))} == "
          f"app {tuple(hex(x) for x in app_areas.get(name, (0, 0)))}")

slot = app_areas["image-0"][1]
check(len(signed) + 0x1000 <= slot,
      f"signed app {len(signed)} B leaves room in the {slot // 1024} KB slot for the trailer")
check(app_areas["image-1"][1] == slot, "both image slots are the same size")
check(app_areas["image-0"][0] + slot <= app_areas["image-1"][0], "image-0 does not overlap image-1")
check(app_areas["image-1"][0] + app_areas["image-1"][1] <= app_areas["storage"][0],
      "image-1 does not overlap storage")

flash = int(re.search(r"CONFIG_FLASH_SIZE=(\d+)", ac).group(1))
print(f"NOTE  app build still assumes flash size {flash // (1024 * 1024)} MB; the chip has 4 MB (OTA_PLAN.md)")
last = max(o + s for o, s in app_areas.values())
check(last <= 4 * 1024 * 1024, f"last partition ends at 0x{last:06x}, inside 4 MB")

print()
print("PHASE 1 BUILD CHECKS", "PASSED" if ok else "FAILED")
sys.exit(0 if ok else 1)
EOF
