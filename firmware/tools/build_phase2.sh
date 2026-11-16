#!/usr/bin/env bash
# OTA phase 2 (firmware/OTA_PLAN.md): build the images for the revert test.
#
# Builds the bootloader once and five app variants, all signed with the MCUboot development
# key and the same swap-using-scratch layout as phase 1. Output: firmware/build/phase2/images/
#
#   good-1.confirmed.bin    version 1.0.0, flashed to slot 0 as the known-good starting point
#   good-2.signed.bin       version 2.0.0, healthy: must swap in, confirm itself, stay
#   bad-crash.signed.bin    version 3.0.0, crashes at boot:         must revert to good
#   bad-hang.signed.bin     version 3.0.0, hangs with watchdog:     must revert to good
#   bad-noconfirm.signed.bin version 3.0.0, runs but never confirms: must revert to good
#   bad-selftest.signed.bin version 3.0.0, self-test always fails:  must revert to good
#
# Fault injection (CONFIG_TOMS_TRIAL_FAULT_*) exists only in the bad-* images. The good images
# are built with it off and are the only ones that may ever be flashed to a real unit.
set -euo pipefail
export PATH="/d/esp_tool/tools/cmake/3.30.2/bin:/d/esp_tool/tools/ninja/1.12.1:/d/toms_tools/dtc_pkg/mingw64/bin:/d/zephyr-sdk-0.17.2/xtensa-espressif_esp32s3_zephyr-elf/bin:$PATH"

ROOT=/d/TOMS/firmware/build/phase2
APP=/d/TOMS/firmware/return-machine
IMG=$ROOT/images
KEY=D:/zephyrproject/bootloader/mcuboot/root-ec-p256.pem
IMGTOOL=D:/zephyrproject/.venv/Scripts/imgtool.exe
SLOT=$((0x150000))

mkdir -p "$IMG"
cd /d/zephyrproject

build() { # name version tag fault-symbol-or-empty
	local name=$1 ver=$2 tag=$3 fault=${4:-}
	local dir=$ROOT/$name
	echo "=== build $name (version $ver, tag $tag, fault ${fault:-none})"
	local faultarg=""
	[ -n "$fault" ] && faultarg="-Dreturn-machine_CONFIG_${fault}=y"
	python -m west build -p always -b esp32s3_devkitc/esp32s3/procpu "$APP" \
		--build-dir "$dir" --sysbuild -- \
		-DSB_CONFIG_MCUBOOT_MODE_SWAP_SCRATCH=y \
		-DSB_CONFIG_BOOT_SIGNATURE_TYPE_ECDSA_P256=y \
		-Dreturn-machine_CONFIG_TOMS_BUILD_TAG="\"$tag\"" \
		-Dreturn-machine_CONFIG_MCUBOOT_IMGTOOL_SIGN_VERSION="\"$ver\"" \
		$faultarg > "$ROOT/$name.log" 2>&1 || { tail -20 "$ROOT/$name.log"; exit 1; }
	grep -q "CONFIG_TOMS_BUILD_TAG=\"$tag\"" "$dir/return-machine/zephyr/.config" || { echo "tag did not apply"; exit 1; }
	if [ -n "$fault" ]; then
		grep -q "CONFIG_$fault=y" "$dir/return-machine/zephyr/.config" || { echo "fault $fault did not apply"; exit 1; }
	else
		grep -q "CONFIG_TOMS_TRIAL_FAULT_NONE=y" "$dir/return-machine/zephyr/.config" || { echo "fault not off"; exit 1; }
	fi
}

sign() { # name version outfile [--confirm|--pending]
	# --confirm : padded to the full slot, image_ok set (a known-good primary image)
	# --pending : padded to the full slot, magic set, image_ok NOT set. Written to the update
	#             slot with esptool, MCUboot treats this as "run on trial" at the next reset.
	# (none)    : unpadded, for uploading over Bluetooth later
	local name=$1 ver=$2 out=$3 mode=${4:-}
	local raw=$ROOT/$name/return-machine/zephyr/zephyr.bin
	local flags=(--version "$ver" --header-size 0x20 --slot-size $SLOT --align 4 --key $KEY)
	[ "$mode" = "--confirm" ] && flags+=(--pad --confirm)
	[ "$mode" = "--pending" ] && flags+=(--pad)
	$IMGTOOL sign "${flags[@]}" "$raw" "$IMG/$out" > /dev/null
}

build good-1 1.0.0 good-1 ""
build good-2 2.0.0 good-2 ""
build bad-crash 3.0.0 bad-crash TOMS_TRIAL_FAULT_CRASH
build bad-hang 3.0.0 bad-hang TOMS_TRIAL_FAULT_HANG
build bad-noconfirm 3.0.0 bad-noconfirm TOMS_TRIAL_FAULT_NO_CONFIRM
build bad-selftest 3.0.0 bad-selftest TOMS_TRIAL_FAULT_SELFTEST_FAIL

cp "$ROOT/good-1/mcuboot/zephyr/zephyr.bin" "$IMG/mcuboot.bin"
sign good-1 1.0.0 good-1.confirmed.bin --confirm
sign good-2 2.0.0 good-2.signed.bin
sign bad-crash 3.0.0 bad-crash.signed.bin
sign bad-hang 3.0.0 bad-hang.signed.bin
sign bad-noconfirm 3.0.0 bad-noconfirm.signed.bin
sign bad-selftest 3.0.0 bad-selftest.signed.bin
for n in good-2:2.0.0 bad-crash:3.0.0 bad-hang:3.0.0 bad-noconfirm:3.0.0 bad-selftest:3.0.0; do
	sign "${n%%:*}" "${n##*:}" "${n%%:*}.pending.bin" --pending
done

python - "$IMG" <<'EOF'
import hashlib, struct, sys, glob, os
img = sys.argv[1]
print("\nimages:")
for f in sorted(glob.glob(img + "/*.bin")):
    d = open(f, "rb").read()
    if d[:4] == bytes.fromhex("3db8f396"):
        mj, mn, rev, bld = struct.unpack_from("<BBHI", d, 20)
        print(f"  {os.path.basename(f):28} {len(d):>8} B  MCUboot header, version {mj}.{mn}.{rev}")
    else:
        print(f"  {os.path.basename(f):28} {len(d):>8} B  (bootloader, ESP image magic 0x{d[0]:02x})")
EOF
