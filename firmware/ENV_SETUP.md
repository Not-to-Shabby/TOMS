# Return Machine — build environment (Windows host)

State as of 2026-10-27. Everything lives on D: because the C: drive is full.
Reproduce from scratch with `firmware/tools/setup_zephyr_env.sh` (then
`setup_zephyr_env2.sh`, `setup_zephyr_env3.sh` — run in order; each is
idempotent) and `firmware/tools/build_devkitc.sh` for builds.

| Piece | Where | Notes |
| --- | --- | --- |
| west workspace | `D:\zephyrproject` | Zephyr v4.2.0 + all modules; outside the repo, never committed |
| Zephyr SDK | `D:\zephyr-sdk-0.17.2` | `west sdk install -b /d -t xtensa-espressif_esp32s3_zephyr-elf`; its `setup.cmd` was run with `/t xtensa-espressif_esp32s3_zephyr-elf /h /c` (needs wget: `scoop install wget`) |
| Host tools | `D:\esp_tool\tools\cmake\3.30.2`, `...\ninja\1.12.1` | Reused from the existing ESP-IDF install; must be on PATH |
| dtc | `D:\toms_tools\dtc_pkg\mingw64\bin` | The Zephyr SDK has **no Windows host tools** ("not available yet"), so dtc 1.8.1 comes from the msys2 mingw64 package (dtc + libyaml, downloaded from repo.msys2.org) |
| Python | 3.12 (C:) | `pip install -r zephyr/scripts/requirements.txt`, plus `py7zr imgtool smpmgr` |
| BLE controller | blobs under `modules/hal/espressif/zephyr/blobs/lib/esp32s3/` | `west blobs fetch hal_espressif` — **required**; without them CONFIG_BT is unselectable on the S3 |

Build: `bash firmware/tools/build_devkitc.sh` (sets PATH, runs
`west build -p always -b esp32s3_devkitc/esp32s3/procpu
firmware/return-machine --build-dir firmware/build/devkitc`). Verified
2026-10-27: clean build, `zephyr.bin` ≈ 359 KB with the BLE controller linked.

Gotchas hit and fixed (write them down, they cost real time):

1. An app-level `Kconfig` **is the root of the Kconfig universe** — it must
   end with `source "Kconfig.zephyr"` or every core symbol reports
   "undefined".
2. `west espressif install` no longer exists in v4.2; the toolchain comes
   from `west sdk install`. Valid toolchain name:
   `xtensa-espressif_esp32s3_zephyr-elf`.
3. The v4.2 display path is a `zephyr,mipi-dbi-spi` controller node holding
   the `sitronix,st7735r` device (`dc-gpios` lives on the controller);
   SPI pinctrl macros are `SPIM3_*` (not `SPI3_*`); the S3 pinctrl includes
   are `esp-pinctrl-common.h` + `dt-bindings/pinctrl/esp32s3-pinctrl.h` +
   `esp32s3-gpio-sigmap.h`.
4. `bt_id_get` returns void in 4.x; `CONFIG_BT_BUF_EVT_RX_COUNT` must be
   greater than `CONFIG_BT_BUF_ACL_TX_COUNT` (static assert).
5. `K_WORK_DELAYABLE_DEFINE(work, NULL)` compiles but crashes when the work
   runs — the handler must be a real function (bit us in buzzer.c and
   button.c).

C: drive is 100% full (a few hundred MB free; pip temp lives there). Builds
run from D:, but pip and Python temp still touch C: — clean it up before any
further installs.
