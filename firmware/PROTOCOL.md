# Return Machine — BLE Protocol Specification (v1.0 Final)

**Status: FINALIZED (Approved for Conductor App Integration)**  
This specification defines the production Bluetooth Low Energy (BLE) interface between the **TOMS Return Machine (RM)** firmware (ESP32-S3 / Zephyr 4.2.0) and the **TOMS Android Conductor Application** (`toms_android/app`).

---

## 1. Transport & Link Layer

* **Role**: The Return Machine operates as a **BLE 5.0 Peripheral / GATT Server**. The Conductor's smartphone acts as the **Central / GATT Client**.
* **Advertising**:
  * Broadcast Name: `TOMS-RM-<last 4 hex digits of MAC>` (e.g. `TOMS-RM-3C48`).
  * Service UUID `544F4D53-5245-5455-524E-414348494E45` included in advertising data.
  * Connectable, 1-second advertising interval when disconnected.
* **Security & Bonding (LE Secure Connections)**:
  * Firmware enforces `BT_GATT_PERM_WRITE_ENCRYPT` on all protected operational characteristics.
  * Security Level: **BT_SECURITY_L2** (Authenticated, encrypted link via Numeric Comparison / Passkey).
  * The board automatically confirms the pairing handshake on the BLE bus while rendering the 6-digit passkey on the LCD display.
  * Bond keys persist in the Return Machine's non-volatile storage (`storage` NVS partition) and survive reboots and OTA upgrades.
  * Unauthenticated writes from unbonded centrals are rejected with `BT_ATT_ERR_AUTHENTICATION` (0x05) or `BT_ATT_ERR_WRITE_NOT_PERMITTED` (0x03).
* **MTU & Throughput**:
  * Standard negotiated ATT MTU: **247 bytes** (minimum fallback MTU 65).
  * Effective GATT payload: $\text{MTU} - 3 \text{ (ATT header)} = \mathbf{244\text{ bytes}}$ per transaction.
  * Connection priority: Central requests `CONNECTION_PRIORITY_HIGH` (11.25 ms connection interval).
* **Byte Order**: Little-endian across all fields unless explicitly noted.

---

## 2. GATT Service Layout

**Primary Service UUID**: `544F4D53-5245-5455-524E-414348494E45` ("TOMS RETURN MACHINE")

Characteristics share the 128-bit base UUID `544F4D53-5245-5455-524E-414348494Exx` with a 1-byte suffix `xx`:

| Suffix | Characteristic Name | Properties | Permissions | Direction | Description |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `0x01` | `card_read` | `NOTIFY` | Read / CCCD Encrypted | RM → Phone | Notifies passenger card taps read by PN532 |
| `0x02` | `state_update` | `WRITE`, `WRITE_NO_RESP` | Encrypted Write | Phone → RM | Pushes fare/card state table (snapshot or delta) |
| `0x03` | `paid_check` | `NOTIFY` | Read / CCCD Encrypted | RM → Phone | RM queries phone for instant card payment verdict |
| `0x04` | `paid_reply` | `WRITE`, `WRITE_NO_RESP` | Encrypted Write | Phone → RM | Phone answers paid check with payment verdict |
| `0x05` | `heartbeat` | `NOTIFY` | Read / CCCD Encrypted | RM → Phone | 10-second periodic telemetry (battery, NFC, uptime) |
| `0x06` | `time_sync` | `WRITE` | Encrypted Write | Phone → RM | Synchronizes UTC epoch seconds to RM RTC clock |
| `0x07` | `config` | `WRITE` | Encrypted Write | Phone → RM | Updates timeouts, buzzer tone mask, vehicle ID |
| `0x08` | `ack` | `WRITE` | Encrypted Write | Phone → RM | Acknowledges applied state tables or read buffers |
| `0x09` | `device_info` | `READ` | Open / Unauthenticated | RM → Phone | Protocol version, FW release version, dev flags |
| `0x0A` | `rm_ack` | `NOTIFY` | Read / CCCD Encrypted | RM → Phone | RM acknowledges applied `state_update` versions |
| `0x0B` | `sim_card` | `WRITE` | Encrypted Write | Phone → RM | Simulates card tap (rejected if `DEV_MODE=n`) |
| `0x0C` | `ota_control` | `WRITE`, `NOTIFY` | Encrypted Write / CCCD | Phone ↔ RM | BLE OTA firmware update handshake (`START`/`FINISH`) |
| `0x0D` | `ota_data` | `WRITE_NO_RESP` | Encrypted Write | Phone → RM | High-speed binary chunk streaming (up to 240B) |
| `0x0E` | `diag` | `WRITE`, `WRITE_NO_RESP`, `NOTIFY` | Encrypted Write / CCCD | Phone ↔ RM | Hardware test triggers (buzzer, display, PN532 ping) |
| `0x0F` | `auth_token` | `WRITE`, `NOTIFY` | Encrypted Write / CCCD | Phone ↔ RM | Conductor shift authorization token verification |

---

## 3. Message Formats & Wire Protocols

### 3.1 `device_info` (`0x09`, RM → Phone, Read)
Must be read by the Android app immediately upon connection:
```
[0]     protocol_version (uint8, currently 1)
[1]     fw_major (uint8)
[2]     fw_minor (uint8)
[3]     fw_patch (uint8)
[4]     flags (uint8: bit 0 = dev_mode active, bit 1 = values signed)
[5..6]  max_state_entries (uint16, LE, 128)
```

---

### 3.2 `time_sync` (`0x06`, Phone → RM, Write)
The phone writes this immediately after connection to sync the RM clock:
```
[0..3]  epoch (uint32, LE UTC seconds)
[4..5]  remaining_ms (uint16, LE sub-second milliseconds 0..999)
```

---

### 3.3 `card_read` (`0x01`, RM → Phone, Notify)
Notified when a passenger taps an NFC card on the PN532 reader:
```
[0..3]  read_seq (uint32, LE monotonic persistent read sequence number)
[4..7]  timestamp (uint32, LE UTC seconds from RM clock, 0 if unsynced)
[8..11] boot_ms (uint32, LE milliseconds since ESP32 boot)
[12]    uid_len (uint8, 4, 7, or 10 bytes)
[13..N] uid (uid_len bytes, big-endian as read from card)
[N+1]   atype (uint8: 1=Type 2/NTAG, 2=Type 4/DESFire, 3=MIFARE Classic, 0=Other)
[N+2]   state_cached (uint8: current cached state from local table, 0xFF if unknown)
```
*Deduplication Rule*: The RM automatically deduplicates physical card reads — the same UID within 2,000 ms is played locally as `TOMS_TONE_DUPLICATE` and is not re-notified over BLE.

---

### 3.4 `paid_check` (`0x03`, RM → Phone, Notify) & `paid_reply` (`0x04`, Phone → RM, Write)
When a card is tapped, the RM asks the phone for payment verification:

**`paid_check` Request (RM → Phone)**:
```
[0..3]  check_seq (uint32, LE monotonic transaction ID)
[4]     uid_len (uint8)
[5..N]  uid (uid_len bytes)
```

**`paid_reply` Response (Phone → RM)**:
```
[0..3]  check_seq (uint32, matching request ID)
[4]     verdict (uint8)
```
**Payment Verdict Codes**:
* `0`: `TOMS_VERDICT_OK` — Fare paid / valid passenger. Plays `TOMS_TONE_OK`.
* `1`: `TOMS_VERDICT_UNPAID` — Passenger owes fare. Plays `TOMS_TONE_UNPAID` alarm; conductor alerted.
* `2`: `TOMS_VERDICT_UNKNOWN` — Card unrecognized. Plays `TOMS_TONE_UNKNOWN`.
* `3`: `TOMS_VERDICT_ALREADY_RETURNED` — Card already scanned at exit. Plays `TOMS_TONE_DUPLICATE`.

*Timeout Fallback*: The RM waits `paid_timeout_ms` (default 1,500 ms). If BLE is disconnected or the phone does not respond within the timeout, the RM falls back to its local cached state:
* `ASSIGNED_PAID` (`2`) $\rightarrow$ `TOMS_TONE_OK`
* `RETURNED_UNPAID` (`4`) $\rightarrow$ `TOMS_TONE_UNPAID`
* Any other status $\rightarrow$ `TOMS_TONE_UNVERIFIED` (two short 60 ms beeps).

---

### 3.5 `state_update` (`0x02`, Phone → RM, Write) & `rm_ack` (`0x0A`, RM → Phone, Notify)
Pushes passenger card states from the phone to the Return Machine's local table (up to 128 cards):
```
[0..3]  table_version (uint32, LE monotonic counter)
[4]     flags (uint8: bit 0 = 1 for full snapshot, 0 for delta update)
[5]     count (uint8: number of card entries following)
Per entry:
  [..]  uid_len (uint8, 4 or 7)
  [..]  uid (uid_len bytes)
  [..]  status (uint8: 0=available, 1=assigned_unpaid, 2=assigned_paid, 3=returned, 4=returned_unpaid, 5=lost)
```
*Acknowledgment*: Upon applying the update, the RM notifies `rm_ack` (`0x0A`):
```
[0]     kind (uint8, 1 = state_ack)
[1..4]  table_version (uint32, LE version applied)
```

---

### 3.6 `heartbeat` (`0x05`, RM → Phone, Notify)
Dispatched by the RM every 10 seconds:
```
[0..3]  uptime_s (uint32, LE seconds since boot)
[4]     battery_pct (uint8, 0..100%, 0xFF if operating on external USB 5V)
[5..6]  battery_mv (uint16, LE millivolts, 0 if on USB)
[7]     nfc_status (uint8: 0 = PN532 Online/Ready, 3 = Hardware Init Error)
[8..11] state_version (uint32, LE active state table version)
[12..13] buffered_reads (uint16, LE offline reads stored in flash)
[14]    reset_reason (uint8: 0=power-on, 1=brownout, 2=watchdog, 3=software, 4=pin, 5=other)
```

---

### 3.7 `diag` (`0x0E`, Phone ↔ RM, Write/Notify)
Hardware diagnostic test bench commands:

**Diagnostic Write (Phone → RM)**:
```
[0]     cmd (uint8)
[1]     arg (uint8)
```
* `cmd = 0x01`: Play Buzzer Tone (`arg`: 0=Boot, 1=OK, 2=Warn, 3=Unkn, 4=Unvf, 5=Dup)
* `cmd = 0x02`: Display Next Page
* `cmd = 0x03`: Toggle LCD Backlight
* `cmd = 0x04`: Query PN532 NFC Hardware Health

**Diagnostic Notification (RM → Phone)**:
* `[0x81, count_low, count_high, 0x01]`: Physical Button Pressed (`count`: uint16 button presses)
* `[0x84, status, ic, ver_major, ver_minor, 0x00]`: PN532 Health Response (`status`: 0=OK, `ic`: 0x32, `ver`: 1.6)

---

### 3.8 `auth_token` (`0x0F`, Phone → RM, Write/Notify)
Single-packet HMAC-SHA256 Conductor Shift Authorization:
```
[0..15]   vehicle_id (16 bytes UUID)
[16..19]  valid_until (uint32, LE UTC epoch seconds)
[20..23]  capabilities (uint32 bitmask: 0x1=CARDS, 0x2=FARES, 0x4=CONFIG, 0x8=OTA)
[24..39]  signature (16 bytes, truncated HMAC-SHA256 over bytes 0..23)
```

---

## 4. Audio Feedback Specifications

The Return Machine uses a fixed-frequency active piezo buzzer (GPIO6):

| Tone Enum | Name | Acoustic Pattern | Typical Meaning |
| :--- | :--- | :--- | :--- |
| `0` | `TOMS_TONE_BOOT` | 3 quick rising beeps (30 ms) | Power-on / BLE connected / PN532 init OK |
| `1` | `TOMS_TONE_OK` | 1 × 80 ms clean beep | Passenger fare valid & confirmed |
| `2` | `TOMS_TONE_UNPAID` | 3 × 200 ms beeps (150 ms gaps) | Fare unpaid; alert conductor |
| `3` | `TOMS_TONE_UNKNOWN` | 2 × 300 ms beeps (100 ms gaps) | Card unregistered in fleet system |
| `4` | `TOMS_TONE_UNVERIFIED` | 2 × 60 ms short staccato beeps | Offline fallback read / unverified fare |
| `5` | `TOMS_TONE_DUPLICATE`| 1 × 400 ms long beep | Duplicate exit scan within 2.0s |

---

## 5. Conductor App Connection Lifecycle

```
┌──────────────┐                               ┌──────────────┐
│ Conductor App│                               │Return Machine│
└──────┬───────┘                               └──────┬───────┘
       │                                              │
       │ 1. BLE Scan (Filter by Service 0x45)         │
       ├─────────────────────────────────────────────►│
       │                                              │
       │ 2. Connect & Request MTU 247                 │
       ├─────────────────────────────────────────────►│
       │                                              │
       │ 3. Read device_info (0x09)                   │
       ├─────────────────────────────────────────────►│
       │    Verify protocol_version == 1              │
       │◄─────────────────────────────────────────────┤
       │                                              │
       │ 4. Link Encryption / LE Secure Connections   │
       │    (createBond -> Passkey Auto-Confirmed)    │
       ├─────────────────────────────────────────────►│
       │                                              │
       │ 5. Subscribe to Notifications (CCCD):        │
       │    card_read (0x01), paid_check (0x03),      │
       │    heartbeat (0x05), rm_ack (0x0A), diag     │
       ├─────────────────────────────────────────────►│
       │                                              │
       │ 6. Write time_sync (0x06)                    │
       │    Sync RTC clock with smartphone time       │
       ├─────────────────────────────────────────────►│
       │                                              │
       │ 7. Write state_update (0x02, snapshot=1)     │
       │    Push full passenger fare registry         │
       ├─────────────────────────────────────────────►│
       │    RM answers with rm_ack (0x0A)             │
       │◄─────────────────────────────────────────────┤
       │                                              │
       │ ======== NORMAL IN-SERVICE OPERATION ======= │
```
