# Archive: TOMS v1 (Flutter and ESP32 Master/Slave)

The first generation of TOMS, kept for reference. It is not maintained and is not part of v2.

| Folder | What it is |
| --- | --- |
| `toms_mobile/` | Flutter app. Talked to the Master over USB serial; had no phone-side NFC. |
| `esp32_master/` | ESP32-S3 Master firmware (ESP-IDF, PlatformIO). Reads cards over NFC-DEP, relays to the phone. |
| `esp32_slave/` | ESP32-S3 Slave (seat terminal) firmware: PN532 target mode, TFT UI. |
| `shared_lib/` | C libraries both firmware projects use: PN532 driver, packet protocol, crypto, ESP-NOW. |

`shared_lib` sits next to the firmware on purpose: both projects reach it with `../shared_lib` and `../../shared_lib`, so the three folders must stay together.

**Why archived:** v2 replaces the Master/Slave topology with a phone that reads the card itself (`toms_android/`) and a separate Return Terminal. See `documentation/v2_decisions.md`.

**What is still useful:** `shared_lib/pn532` (the I2C driver) is a starting point for the Return Terminal if ESP-IDF is chosen; under Zephyr it needs a port. The fare logic in `toms_mobile/lib/services/session_service.dart` is the old behavior the Kotlin tests call `LEGACY_OLD_APP`. It is not a verified fare.

**History:** the full state before the move is on the `legacy/flutter-final` branch and the `v1-flutter-legacy` tag. Files were moved with `git mv`, so `git log --follow` still works.

**Not here:** the hardware drawings stay in `hardware/` and the older design papers in `documentation/`. The ESP-NOW variants (`esp32_*_espnow`, `esp32_* - ESPNOW`) and `stm32_slave_monitor` were never committed and exist only on the original developer's machine.
