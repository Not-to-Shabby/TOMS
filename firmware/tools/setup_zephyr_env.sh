#!/usr/bin/env bash
# One-shot Zephyr environment setup for the Return Machine (Windows host).
# Installs the west workspace at D:\zephyrproject (outside the TOMS repo),
# the Python requirements, the signing/SMP CLIs, and the Espressif binary
# toolchain for the ESP32-S3. Safe to re-run; each step is idempotent.
set -e

echo "== $(date -u) setting git longpaths (required for Zephyr on Windows)"
git config --global core.longpaths true

if [ ! -d /d/zephyrproject/zephyr ]; then
	echo "== $(date -u) west init (zephyr v4.2.0)"
	python -m west init -m https://github.com/zephyrproject-rtos/zephyr --mr v4.2.0 /d/zephyrproject
else
	echo "== $(date -u) west workspace already initialized"
fi

cd /d/zephyrproject
echo "== $(date -u) west update"
west update

echo "== $(date -u) pip requirements"
python -m pip install -r zephyr/scripts/requirements.txt

echo "== $(date -u) signing and SMP CLIs"
python -m pip install imgtool smpmgr

echo "== $(date -u) espressif toolchain for esp32s3"
west espressif install --toolchains esp32s3

echo "== $(date -u) DONE"
