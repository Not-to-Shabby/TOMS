# stm32_slave_monitor

> STM32F103C8T6 (Blue Pill) debug monitor for the **TOMS ESP32 Slave – ESPNOW** firmware.
>
> Receives JSON status packets from the slave over UART, drives visual LEDs,
> and forwards all traffic to a PC via USB CDC for real-time shift debugging.

---

## Wiring

```
ESP32-S3 Slave               Blue Pill (STM32F103C8T6)
──────────────────           ──────────────────────────
GPIO17  (TX)     ──────────► PA3  (UART2 RX)   ← debug input
GPIO18  (RX)     ◄────────── PA2  (UART2 TX)   → future cmds (optional)
GND              ──────────  GND                ← REQUIRED common ground
```

> ⚠️ Both boards run at **3.3 V** — no level shifter needed.

### LED Indicators

| Blue Pill Pin | LED Color | Meaning |
|---|---|---|
| `PC13` | White (onboard) | Alive — blinks at 1 Hz |
| `PB0` | 🟢 Green | ESP-NOW master link active |
| `PB1` | 🟡 Yellow | Boarding in progress (fare / QR screen) |
| `PB12` | 🔴 Red | Timeout or error state |

Wire LEDs with a 330Ω resistor between PB0/PB1/PB12 and LED anode, LED cathode to GND.

---

## Setup

### 1. Flash the STM32

```bash
cd stm32_slave_monitor
pio run --target upload
```

Requires an **ST-Link V2** dongle connected to the Blue Pill's SWD pins (SWDIO, SWCLK, GND).

### 2. Flash the ESP32 Slave

The slave firmware (`esp32_slave - ESPNOW`) already has the debug UART integrated.  
Build and flash as normal:

```bash
cd "esp32_slave - ESPNOW"
idf.py build flash monitor
```

The debug UART is on **GPIO17 TX** at **115200 baud**.  
It is independent of the ESP-IDF logging UART (GPIO43, UART0).

### 3. Connect to PC

Plug the Blue Pill's **USB port** (PA11/PA12) into the PC.  
A COM port appears. Open any serial terminal at **115200 baud**:

```bash
# Linux / macOS
screen /dev/ttyACM0 115200

# Windows
# Use PuTTY or the Arduino Serial Monitor on the new COM port
```

---

## Packet Reference

All packets are **newline-delimited JSON** emitted by the ESP32 slave.

| `"dbg"` value | When it fires | Key fields |
|---|---|---|
| `"boot"` | Slave startup | `msg` |
| `"state"` | After screen change or boarding completes | `screen`, `batt_mv`, `batt_pct`, `master`, `seq` |
| `"btn"` | Every button event | `event`: PRESS / LONG_PRESS / HOLD_1S / HOLD_2S / HOLD_3S / RELEASE |
| `"espnow_rx"` | ESP-NOW packet received | `type`, `fare`, `seat` |
| `"espnow_tx"` | ESP-NOW packet sent | `type`, `seq` |
| `"batt"` | Heartbeat battery report | `mv`, `pct` |
| `"timeout"` | Board wait or release ACK expired | `reason`: `board_wait` / `release_ack` |
| `"error"` | Unexpected error | `msg` |

### Example Session

```json
{"dbg":"boot","msg":"TOMS Slave debug UART ready"}
{"dbg":"btn","event":"PRESS"}
{"dbg":"espnow_tx","type":"BUTTON_PRESS","seq":3}
{"dbg":"espnow_rx","type":"BOARD_COMMAND","fare":1300,"seat":2}
{"dbg":"state","screen":"fare","batt_mv":3820,"batt_pct":78,"master":"AA:BB:CC:DD:EE:FF","seq":4}
{"dbg":"btn","event":"HOLD_1S"}
{"dbg":"btn","event":"HOLD_2S"}
{"dbg":"btn","event":"HOLD_3S"}
{"dbg":"espnow_tx","type":"RELEASE","seq":5}
{"dbg":"espnow_rx","type":"ACK_MASTER","fare":0,"seat":0}
{"dbg":"state","screen":"welcome","batt_mv":0,"batt_pct":0,"master":"AA:BB:CC:DD:EE:FF","seq":6}
{"dbg":"batt","mv":3820,"pct":78}
```

### STM32 Monitor Output (USB CDC)

```
[TOMS-MON] STM32 Slave Monitor ready
[TOMS-MON] Waiting for ESP32 Slave debug packets on UART2 (PA3 RX)...
[RAW] {"dbg":"boot","msg":"TOMS Slave debug UART ready"}
[RAW] {"dbg":"btn","event":"PRESS"}
[TOMS-MON] BTN[PRESS]   | screen=UNKNOWN       link=NO  batt=0mV(0%) fare=0 seat=0
[RAW] {"dbg":"espnow_rx","type":"BOARD_COMMAND","fare":1300,"seat":2}
[TOMS-MON] ESPNOW_RX[BOARD_COMMAND] | screen=UNKNOWN  link=YES batt=0mV(0%) fare=1300 seat=2
[RAW] {"dbg":"state","screen":"fare",...}
[TOMS-MON] STATE        | screen=FARE          link=YES batt=3820mV(78%) fare=1300 seat=2
```

---

## Build Flags

To disable all debug UART overhead in a production build, add to `sdkconfig` or `CMakeLists.txt`:

```cmake
# In esp32_slave - ESPNOW/CMakeLists.txt top level
target_compile_definitions(${COMPONENT_TARGET} PRIVATE TOMS_DEBUG_SERIAL_ENABLED=0)
```

Or set in `sdkconfig.defaults`:
```
CONFIG_TOMS_DEBUG_SERIAL_ENABLED=n
```

---

## Project Structure

```
stm32_slave_monitor/
├── platformio.ini          # PlatformIO: bluepill_f103c8, Arduino + USB CDC
└── src/
    └── main.cpp            # Full monitor firmware: UART2 → parse → LED + USB CDC

esp32_slave - ESPNOW/main/
└── debug_serial/
    ├── debug_serial.h      # Public API + build-time enable/disable
    └── debug_serial.c      # UART1 driver + JSON packet builders
```
