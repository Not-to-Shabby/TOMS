# Return Machine — Zephyr spike (1–2 days, hardware needed)

Purpose: decide Zephyr vs ESP-IDF for the Return Machine before the production build.
Each item is pass/fail with evidence. If any **hard** item fails, the fallback is ESP-IDF
with NimBLE and the custom OTA described in the plan doc; record the reason here — the
decision is named once, consistently, in Chapters III and IV of the paper.

Hardware gates before any flash (Fork confirms): buck output measured 3.3 V, PN532 set to
I2C mode, module variant read (`esptool flash_id`), backlight polarity determined. See
`HW_NOTES.md` for the checklist.

Spike test client: nRF Connect on the Android phone (Pova 4 or the Xiaomi).

| # | Item | Hard? | Result | Evidence | Date |
| --- | --- | --- | --- | --- | --- |
| 1 | BLE GATT peripheral: advertises, phone connects, notify + write work, link stays up 1 h | yes | — | — | — |
| 2 | PN532 over I2C in reader mode: version check, then 100 consecutive reliable UID reads (NTAG, DESFire, MIFARE Classic) at exit distance, IRQ-driven | yes | — | — | — |
| 3 | Flash-backed ring buffer (NVS/FCB): no data loss across 20 power cycles | yes | — | — | — |
| 4 | Reconnect: 50 drop/restore cycles, buffered reads flush in order | yes | — | — | — |
| 5 | OTA over BLE: MCUboot swap mode, image swap, confirm/revert on ESP32-S3 (sample docs list only nRF — unproven); upload by Zephyr SMP or the custom service in `OTA_PLAN.md`, which has the phases and pass gates | yes | — | — | — |
| 6 | Watchdog: real application watchdog binding verified (`espressif,esp32-timg-wdt` MWDT + `CONFIG_ESP_TASK_WDT`; the xt-wdt is crystal-fault only); fed only from a loop proving the PN532 task and BLE are alive; reset reason logged in the heartbeat | yes | — | — | — |

## Notes per item

(fill during the spike; keep raw measurements)

## Decision

(record: chosen RTOS, failed items if any, reuse assessment of
`archive/esp32_slave` + `archive/shared_lib/pn532`, and the paragraph for the paper)
