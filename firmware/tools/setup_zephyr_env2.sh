#!/usr/bin/env bash
# Continuation of setup_zephyr_env.sh after the manual west init -l recovery:
# pulls all Zephyr modules, installs Python requirements + CLI tools, and the
# Espressif binary toolchain for the ESP32-S3.
set -e

cd /d/zephyrproject
echo "== $(date -u) west update"
python -m west update

echo "== $(date -u) pip requirements"
python -m pip install -r zephyr/scripts/requirements.txt

echo "== $(date -u) signing and SMP CLIs"
python -m pip install imgtool smpmgr

echo "== $(date -u) espressif toolchain for esp32s3"
python -m west espressif install --toolchains esp32s3

echo "== $(date -u) DONE"
