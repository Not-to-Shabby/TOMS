#!/usr/bin/env bash
# Build the Return Machine app for the closest upstream board (esp32s3_devkitc)
# using the toolchain pieces already on this machine: cmake + ninja from the
# ESP-IDF tools, dtc from the msys2 package under D:\toms_tools, and the
# Espressif xtensa toolchain installed by `west espressif install`.
set -e
export PATH="/d/esp_tool/tools/cmake/3.30.2/bin:/d/esp_tool/tools/ninja/1.12.1:/d/toms_tools/dtc_pkg/mingw64/bin:$PATH"

cd /d/zephyrproject
python -m west build -p always -b esp32s3_devkitc/esp32s3/procpu \
	/d/TOMS/firmware/return-machine \
	--build-dir /d/TOMS/firmware/build/devkitc
