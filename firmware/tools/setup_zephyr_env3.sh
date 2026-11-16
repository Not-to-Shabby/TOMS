#!/usr/bin/env bash
# Remaining Zephyr environment steps after the module pull:
# pip requirements, CLI tools, and the Zephyr SDK (Windows) with the
# xtensa-espressif esp32s3 toolchain + host tools (includes dtc),
# installed on D: because C: is full. Idempotent.
set -e

echo "== $(date -u) pip requirements"
python -m pip install -r /d/zephyrproject/zephyr/scripts/requirements.txt

echo "== $(date -u) 7z support for the SDK installer + CLI tools"
python -m pip install py7zr imgtool smpmgr

echo "== $(date -u) Zephyr SDK (esp32s3 toolchain + host tools) onto D:"
cd /d/zephyrproject
python -m west sdk install -b /d -t xtensa-espressif_esp32s3

echo "== $(date -u) DONE"
