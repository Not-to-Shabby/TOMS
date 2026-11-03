#!/usr/bin/env bash
# Phase 6 Security & Downgrade Prevention Test Build (firmware/OTA_PLAN.md)
#
# Builds target images to validate Phase 6 security gates:
# 1. good-release:       Version 2.0.0, DEV_MODE=n (locks unauthenticated writes & forces downgrade check)
# 2. test-downgrade:     Version 1.0.0 (must be REFUSED by good-release with TOMS_OTA_VERSION)
# 3. test-upgrade:       Version 2.1.0 (must be ACCEPTED by good-release and swap)
# 4. test-wrongkey:      Version 2.1.0 signed with production-ec-p256.pem (must be REJECTED by dev bootloader)

set -euo pipefail
export PATH="/d/esp_tool/tools/cmake/3.30.2/bin:/d/esp_tool/tools/ninja/1.12.1:/d/toms_tools/dtc_pkg/mingw64/bin:/d/zephyr-sdk-0.17.2/xtensa-espressif_esp32s3_zephyr-elf/bin:$PATH"

ROOT=/d/TOMS/firmware/build/phase6
APP=/d/TOMS/firmware/return-machine
IMG=$ROOT/images
KEY_DEV=D:/zephyrproject/bootloader/mcuboot/root-ec-p256.pem
KEY_PROD=D:/TOMS/firmware/keys/production-ec-p256.pem
IMGTOOL=D:/zephyrproject/.venv/Scripts/imgtool.exe
SLOT=$((0x150000))

mkdir -p "$IMG"
cd /d/zephyrproject

build() {
    local name=$1 ver=$2 tag=$3 devmode=$4
    local dir=$ROOT/$name
    echo "=== Building $name (version $ver, tag $tag, DEV_MODE=$devmode) ==="
    
    python -m west build -p always -b esp32s3_devkitc/esp32s3/procpu "$APP" \
        --build-dir "$dir" --sysbuild -- \
        -DSB_CONFIG_MCUBOOT_MODE_SWAP_SCRATCH=y \
        -DSB_CONFIG_BOOT_SIGNATURE_TYPE_ECDSA_P256=y \
        -Dreturn-machine_CONFIG_TOMS_BUILD_TAG="\"$tag\"" \
        -Dreturn-machine_CONFIG_MCUBOOT_IMGTOOL_SIGN_VERSION="\"$ver\"" \
        -Dreturn-machine_CONFIG_TOMS_DEV_MODE="$devmode" \
        > "$ROOT/$name.log" 2>&1 || { tail -30 "$ROOT/$name.log"; exit 1; }

    echo "=== Build $name OK ==="
}

sign() {
    local name=$1 ver=$2 key=$3 out=$4 mode=${5:-}
    local raw=$ROOT/$name/return-machine/zephyr/zephyr.bin
    local flags=(--version "$ver" --header-size 0x20 --slot-size $SLOT --align 4 --key "$key")
    [ "$mode" = "--confirm" ] && flags+=(--pad --confirm)
    [ "$mode" = "--pending" ] && flags+=(--pad)
    "$IMGTOOL" sign "${flags[@]}" "$raw" "$IMG/$out" > /dev/null
    echo "Signed: $IMG/$out"
}

# 1. Base release image (2.0.0, DEV_MODE=n)
build good-release 2.0.0 release-2.0.0 n

# 2. Upgrade image with MTU 247 high-speed BLE and LCD refusal fix (2.3.0, DEV_MODE=n)
build test-upgrade 2.3.0 speed-2.3.0 n

# 3. Production release image with Sliding Card Debounce, Persistent 30s Alarm, and Remote Silence (2.8.0, DEV_MODE=n)
build release-v2.8.0 2.8.0 TOMS-v2.8.0 n

# Sign images
echo "=== Signing Phase 6 test images ==="
# Bootloader from good-release
cp "$ROOT/good-release/mcuboot/zephyr/zephyr.bin" "$IMG/mcuboot.bin"

# good-release.signed.bin (for BLE OTA flashing onto current board)
sign good-release 2.0.0 "$KEY_DEV" good-release.signed.bin
sign good-release 2.0.0 "$KEY_DEV" good-release.confirmed.bin --confirm

# test-upgrade.signed.bin (version 2.3.0, valid high-speed upgrade)
sign test-upgrade 2.3.0 "$KEY_DEV" test-upgrade.signed.bin

# test-wrongkey.signed.bin (version 2.4.0 > 2.3.0; signed with production key so dev bootloader rejects)
sign test-upgrade 2.4.0 "$KEY_PROD" test-wrongkey.signed.bin

# firmware-v2.8.0.signed.bin (version 2.8.0, high-speed + PN532 ping + battery + INSERT CARD UI + Debounce + 30s Alarm)
sign release-v2.8.0 2.8.0 "$KEY_DEV" firmware-v2.8.0.signed.bin
sign release-v2.8.0 2.8.0 "$KEY_DEV" firmware-v2.8.0.confirmed.bin --confirm

# test-downgrade.signed.bin (version 1.0.0, to test downgrade rejection on good-release)
# We can use the good-1 raw binary or build a downgrade 1.0.0 binary
if [ -f "/d/TOMS/firmware/build/phase2/good-1/return-machine/zephyr/zephyr.bin" ]; then
    "$IMGTOOL" sign --version "1.0.0" --header-size 0x20 --slot-size $SLOT --align 4 --key "$KEY_DEV" \
        "/d/TOMS/firmware/build/phase2/good-1/return-machine/zephyr/zephyr.bin" "$IMG/test-downgrade.signed.bin"
    echo "Signed: $IMG/test-downgrade.signed.bin"
fi

echo "=== All Phase 6 images generated ==="
python - "$IMG" <<'EOF'
import struct, glob, os, sys
img = sys.argv[1]
print("\nGenerated Phase 6 Binaries:")
for f in sorted(glob.glob(img + "/*.bin")):
    d = open(f, "rb").read()
    if d[:4] == bytes.fromhex("3db8f396"):
        mj, mn, rev, bld = struct.unpack_from("<BBHI", d, 20)
        print(f"  {os.path.basename(f):32} {len(d):>8} B  MCUboot header v{mj}.{mn}.{rev}+{bld}")
    else:
        print(f"  {os.path.basename(f):32} {len(d):>8} B  (bootloader)")
EOF
