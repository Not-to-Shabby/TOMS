# Return Machine v2 — hardware notes

Verified against the Fritzing sketch `hardware/return_terminal/Return Terminal Design v2.1.fzz`
on 2026-10-27, exported with `firmware/tools/export_fzz_nets.py` (output:
`firmware/return-machine-v2-netlist.txt`). The brief's pin table and this export agree on
every pin. Where the plan doc text (`documentation/toms-v2-plan-clean.md`) and the sketch
differ, the sketch is recorded below and the item is flagged for bench confirmation.

## Verified pin map (ESP32-S3 Super Mini)

| Function | GPIO | Netlist evidence |
| --- | --- | --- |
| PN532 I2C SDA | 2 | `ESP_TO_RFID_SDA` → PN532 `SDA` |
| PN532 I2C SCL | 7 | `ESP_TO_RFID_SCL` → PN532 `SCL` (moved off strapping GPIO3) |
| PN532 IRQ | 5 | `ESP_TO_RFID_IRQ` → PN532 `IRQ` |
| Button | 4 | GPIO4 — tactile switch to GND, R1 10K pull-up to 3.3V, C6 100 nF to GND. Active low. |
| Buzzer | 6 | GPIO6 — R4 1K → NPN (Q4) base, R7 10K base pull-down, collector → J1 pin (buzzer minus). High = tone on. |
| LCD RST | 8 | `ESP_TO_LCD_RST` → ST7735S `RST` |
| LCD A0/DC | 9 | `ESP_TO_LCD_A0` → ST7735S `A0` |
| LCD CS | 10 | `ESP_TO_LCD_CS` → ST7735S `CS` |
| LCD SDA/MOSI | 11 | `ESP_TO_LCD_SDA` → ST7735S `SDA` |
| LCD SCLK | 12 | `ESP_TO_LCD_SCLK` → ST7735S `SCLK` |
| LCD backlight | 13 | GPIO13 — R3 1K → NPN (Q3) base, collector → LCD `LED` pin, emitter → GND. See backlight OPEN item. |
| 3V3 | — | Fed by the Mini-360 buck output. Decoupling: C1 10 µF bulk + C2, C3, C4, C5 100 nF. |
| GND | — | Common. |
| 5V pin | — | **Unconnected** in the sketch, as required by power route B. |
| GPIO1 | — | **Nothing wired** — reserved for battery sensing (divider or fuel gauge). |

PN532 connections: `GND`, `VCC` (3.3V), `SDA`, `SCL`, `IRQ`. `RSTO` is unconnected — there is
no hardware reset; recovery is re-initialization over I2C. SPI pins (`SCK`, `MISO`, `MOSI`,
`SS`) are unconnected. I2C address is 0x24 (7-bit), as in `archive/shared_lib/pn532`.

## Power chain (route B, verified in the sketch)

2000 mAh cell → DWEII USB-C 5V boost (`A1`) → `5V_BAT_OUT` → Mini-360 buck (`U1`) → 3.3V rail.
The board's battery pads and 5V pin are unused. One charger must ever touch the battery.

## Differences found in the sketch (bench items)

1. **Buzzer high side is on the 5V boost rail, not 3.3V.** J1 pin 1 connects to
   `5V_BAT_OUT+`; J1 pin 0 is the Q4 collector. The plan doc text said "buzzer plus to 3.3V".
   Firmware behavior is unchanged (GPIO6 high = tone on, low-side NPN), but the buzzer needs
   the boost module running. Confirm the buzzer tolerates 5V.
2. **Backlight drive is still the flagged bug.** The sketch has GPIO13 → 1K → NPN (Q3)
   low-side pulling the LCD `LED` pin to GND. If the module's LED pin is the anode (cathode
   grounded on-module), the backlight can never light — exactly the issue the plan doc's v2
   review raises. The parts list says a 2N3906 PNP was bought "used in the build", so the
   physical board may already differ from the sketch. OPEN: bench test (the "100 ohm test").
   Firmware keeps `CONFIG_TOMS_BACKLIGHT_ACTIVE_HIGH` until the polarity is measured.
3. **No I2C pull-ups drawn for PN532 SDA/SCL.** The parts list carries 4.7K pull-ups "if the
   module has none". OPEN: check the module for onboard pull-ups at the bench before relying
   on internal ESP32 pull-ups (too weak for a clean 100 kHz bus).
4. **Battery sense is not wired.** The parts list's 100K divider into GPIO1 is not in the
   sketch. GPIO1 stays reserved; firmware ships the ADC hook but no battery reading until a
   divider or gauge is installed.
5. **Buzzer type unknown** (piezo vs electromagnetic). The sketch has no flyback diode
   across J1; the parts list carries a 1N4148 "electromagnetic buzzer only". OPEN.
6. **Super Mini module variant unknown** (N8 8 MB flash vs N16R8 16 MB + PSRAM). Read with
   `esptool flash_id` at the bench before sizing MCUboot partitions.

## Bench checklist before first flash (Fork confirms)

- [ ] Buck (Mini-360) output measured at 3.3 V with the cell as source.
- [ ] PN532 module switches set to I2C mode.
- [ ] Module variant read (`esptool flash_id`).
- [ ] Backlight polarity determined (which drive circuit is physically installed).
- [ ] PN532 SDA/SCL pull-ups present or added.
- [ ] DWEII boost light-load shutoff checked against the board's idle current.
