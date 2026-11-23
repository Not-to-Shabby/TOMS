# Return Machine — BLE Protocol Specification (v1.0 Final; wire layouts 1 and 2)

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
  * Security Level: see section 8. Level 2 is "encrypted, NOT authenticated" (Just Works): it is enough for the
    GATT write permission but not for diag `00`, `05`, `06`, `07`, `08`, which need level 4 (3 in a build without
    Secure Connections Only).
  * The board confirms the numeric-comparison handshake on its side and renders the 6-digit passkey on the LCD,
    but only while the pairing window is open (button held at power-up), and only for one bond. Section 8.
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
[0]     protocol_version (uint8: the highest wire layout this firmware speaks, currently 2.
                         Every connection starts on layout 1; layout 2 is opt-in, see section 7)
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
[N+3]   outcome (uint8, wire layout 2 ONLY: 0 normal, 1 unverified-timeout, 2 unverified-no-link;
                 section 7). Not present on layout 1.
```
*Deduplication Rule*: The RM automatically deduplicates physical card reads — a card resting in the slot is read once (presence tracking, removal after 400 ms without a read); the same UID put back within 5 s is played locally as `TOMS_TONE_DUPLICATE` and is not re-notified over BLE.

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

*Timeout Fallback*: The RM waits `paid_timeout_ms` (default 1,500 ms). If BLE is disconnected or the phone does not respond within the timeout:
No reply, or no phone, always gives `TOMS_TONE_UNVERIFIED` (two short 60 ms beeps) and the
"unverified" screen, whatever the cached state is. The full alarm is only ever started by a
real `UNPAID` verdict from the phone; the cache is not trusted for that. The cached state is
still sent to the phone in `card_read.state_cached`.

*Alarm*: an `UNPAID` verdict starts the continuous alarm (200 ms on, 150 ms off). It is held
until the phone sends diag `05 00` or the button is pressed (`CONFIG_TOMS_ALARM_TIMEOUT_S`
can add a timeout; default 0 = none). Other tones cannot cancel it. A BLE disconnect or
reconnect does not touch it.

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
[15..16] dropped_reads (uint16, LE, wire layout 2 ONLY, section 7)
[17..18] late_replies  (uint16, LE, wire layout 2 ONLY, section 7)
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
* `cmd = 0x05`: Clear / silence the alarm: `05 00`, and once the machine has a signing key, `05 00` + 8-byte
  signature (section 8.3). Needs an authenticated link.
* `cmd = 0x07`: Select the message layout for this connection: `07 01` or `07 02`. Answered
  `[0x87, status, version_in_force, max_version]` (status 0 ok, 1 unsupported version, 2 malformed;
  on 1 and 2 the layout is unchanged). Section 7.
* `cmd = 0x06`: Provision the signing key: `06` + 32 key bytes. Accepted once, only on an authenticated link; answered with `[0x86, status, 0, 0]` (0 stored, 1 already set, 2 flash error, 0xFF refused). Once set, the key can only be replaced with `08`.
* `cmd = 0x08`: Replace the signing key, 41 bytes, section 8.4. Answered `[0x88, status, grace, 0]`.
* `cmd = 0x00`: open a diagnostic session and turn the machine's diagnostic mode on. Accepted on an authenticated link; answered `[0x80, 0, 1, 0]` (open) or `[0x80, 0, 0, 0xFF]` (refused). Bytes after the command are ignored: the old fixed code is no longer checked, so nothing secret is in the image. The session ends on disconnect.

`0x01`-`0x04` need an open session, the machine's local diagnostic mode (button held 3 s), or a `CONFIG_TOMS_DEV_MODE` build.

**Diagnostic Notification (RM → Phone)**:
* `[0x81, count_low, count_high, 0x01]`: Physical Button Pressed (`count`: uint16 button presses)
* `[0x84, status, ic, ver_major, ver_minor, 0x00]`: PN532 Health Response (`status`: 0=OK, `ic`: 0x32, `ver`: 1.6)

---

### 3.8 `auth_token` (`0x0F`) — documented only, NOT implemented in this firmware
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
       │    protocol_version 2 = layout 2 offered       │
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


---

## 6. Signing and the read log (this firmware)

**Signing.** Once a per-machine key is provisioned (diag `06`), every value the RM notifies
(`card_read`, `heartbeat`, `rm_ack`) gets an 8-byte trailer: the first 8 bytes of
`HMAC-SHA256(key, suffix || payload)` where `suffix` is the characteristic's last UUID byte.
`device_info.flags` bit 1 is set. `RmProtocol.kt` decoders read fixed fields and ignore bytes
after them, so the phone still parses signed frames. The phone does not verify or send
signatures yet: writes without a trailer are accepted, writes with a wrong trailer are
rejected. The key is never in the source or the image; it lives in the settings partition.

**Read log.** `card_read.read_seq` is a persistent counter (numbers are reserved ahead in
flash, so they never repeat across a power cycle). A read delivered live is also given a
number. A read made while the phone is away or not subscribed is stored in a flash record
(128 slots, CRC per record) and sent in `read_seq` order when the phone subscribes. Stored
reads stay until the phone writes `ack` (`0x08`) kind 2 with the highest `read_seq` it has;
unacked reads are offered again on every new connection, so the phone must de-duplicate by
`read_seq`. `heartbeat.buffered_reads` is the stored count. A stored read keeps the paid-check
`outcome` it was made with (section 7). The count of reads overwritten before they were ever
sent on a live link is kept in flash, so it survives power cycles.


---

## 7. Wire layout 2 (opt-in): paid-check outcome and loss counters

Why: the phone could only infer two facts, and has asked for them on the wire. (1) The machine
played the **unverified tone** because the paid-check reply was late or missing. (2) Stored reads
were **dropped** because the flash log was full.

### 7.1 Choosing a layout

The shipped phone is strict: `RmProtocol.frame()` accepts a `card_read` of exactly `15 + uid_len`
bytes and a `heartbeat` of exactly `15` bytes (each plus 8 when signed) and **drops anything else
as "bad size"**. So the machine never sends layout 2 unasked.

```
connect            -> layout 1 (nothing else changes for a phone that does not ask)
phone writes diag  [0x07, 0x02]
RM notifies diag   [0x87, status, version_in_force, max_version]
                    status 0 = ok, 1 = unsupported version, 2 = malformed (layout unchanged)
```
`[0x07, 0x01]` goes back to layout 1. The choice is per connection: a disconnect resets it to
layout 1, so the phone writes it again after every connect. A phone that never sends `0x07` keeps
getting layout 1 for the life of the connection, byte for byte what it gets today.
`device_info.protocol_version` is now 2 = "layout 2 is available". The shipped phone does not
check that field.

### 7.2 `card_read` (`0x01`) layout 2, with `n = uid_len`

| Offset | Size | Field |
| --- | --- | --- |
| 0..3 | 4 | read_seq (uint32 LE) |
| 4..7 | 4 | timestamp (uint32 LE, UTC s, 0 = unsynced) |
| 8..11 | 4 | boot_ms (uint32 LE) |
| 12 | 1 | uid_len (n, 1..10) |
| 13 .. 12+n | n | uid |
| 13+n | 1 | atype |
| 14+n | 1 | state_cached |
| **15+n** | 1 | **outcome (new)** |

Length: `16 + n` unsigned, `24 + n` signed (trailer at `16+n .. 23+n`). Layout 1 is the same
without the last byte (`15 + n`). Example, uid `6F F1 AD 39`, outcome 1 (unverified-timeout):

```
07000000 00f15365 92100000 04 6ff1ad39 01 02 01        (20 bytes; 28 with the trailer)
```

`outcome`: **0** normal (the phone answered in time, whatever the verdict); **1**
unverified-timeout (the check was sent and no reply came within `paid_timeout_ms`, which also
covers a link that dropped while waiting); **2** unverified-no-link (the check could not be sent:
no phone, not subscribed to `paid_check`, or the notify failed). 1 and 2 are exactly the reads
for which the machine played `TOMS_TONE_UNVERIFIED`. A value above 2 is invalid (the machine never
sends one; a parser should drop the frame).

**Order changes in layout 2.** On layout 1 the machine notifies `card_read` first and asks
`paid_check` afterwards. On layout 2 it notifies `paid_check` first and the `card_read` after the
check has ended (at most `paid_timeout_ms`, 1.0 to 2.0 s, later), because the outcome is not known
before. `read_seq` is still handed out at tap time, so the order of `read_seq` numbers is the order
of taps. A read that was stored in flash (no phone, or older reads still waiting) keeps its
outcome and is sent in `read_seq` order like before.

### 7.3 `heartbeat` (`0x05`) layout 2

| Offset | Size | Field |
| --- | --- | --- |
| 0..3 | 4 | uptime_s |
| 4 | 1 | battery_pct (0xFF = not measurable) |
| 5..6 | 2 | battery_mv |
| 7 | 1 | nfc_status |
| 8..11 | 4 | state_version |
| 12..13 | 2 | buffered_reads |
| 14 | 1 | reset_reason |
| **15..16** | 2 | **dropped_reads (new, uint16 LE)** |
| **17..18** | 2 | **late_replies (new, uint16 LE)** |

Length: 19 unsigned, 27 signed (trailer at `19 .. 26`). Layout 1 is the first 15 bytes. Example
(60 s, 85 %, 4132 mV, state v5, 2 buffered, dropped 0x0102, late 0x0304):

```
3c000000 55 2410 00 05000000 0200 00 0201 0403         (19 bytes)
```

* `dropped_reads`: stored reads that were **overwritten before they were ever sent on a live
  link**, because the 128-slot log was full. Saturates at 65535. It is kept in flash, so it
  survives power cycles and counts since the settings partition was last erased: the phone should
  use the **change** between heartbeats, not the absolute value. A read that had already been sent
  live is not counted when its slot is reused (the phone has it).
* `late_replies`: `paid_reply` writes that arrived **after** the machine gave up waiting and played
  the unverified tone, for a check it remembers timing out (the last 8 are remembered). Each is
  counted once. Saturates at 65535, and restarts from 0 at every reboot (RAM only), so use the
  change between heartbeats and treat a drop as a reboot. A late reply never changes the alarm or
  the screen: the unverified tone has already played.

### 7.4 Signing

Both messages are signed as before (section 6): `HMAC-SHA256(key, suffix || payload)` cut to 8 bytes,
appended after the payload. On layout 2 the payload includes the new bytes, so the outcome byte and
both counters are inside the signed region (the host tests flip each one and the check fails). A
layout-2 payload is longer, so a verifier must split the trailer using the **layout in force**:
`16 + n` / `19` for layout 2. Splitting a layout-2 frame with the layout-1 rule treats the last
byte(s) as part of a trailer and fails the signature, so the phone must switch both rules together.

### 7.5 What the phone must change (the machine does not require it; layout 1 keeps working)

1. After connecting and subscribing, write `07 02` to diag and wait for `87 00 02 02`. Do this only
   once the parser below is in place: an unchanged parser drops every layout-2 frame.
2. `RmProtocol.fixedLen`: `card_read` becomes `16 + uid_len` and `heartbeat` becomes `19` when
   layout 2 is in force; keep `15 + uid_len` / `15` for layout 1. `decodeCardReadStrict` and
   `decodeHeartbeatStrict` read `outcome`, `dropped_reads` and `late_replies`.
3. `RmReadIntake`: record `rm_unverified` from `outcome` 1 and 2 instead of inferring it from its
   own timer (keep the timer for layout 1). Use the `dropped_reads` change to record lost reads;
   today the gap check in `onCardRead` only fires when the previous card was LOST.
4. The `paid_check` for a tap now comes **before** the `card_read` for it (7.2): do not assume the
   read is already stored when the check arrives.
5. Treat `outcome > 2` as a bad frame.

### 7.6 Phones that do not know layout 2

* A phone that never writes `07` is unaffected: layout 1, same bytes, same order.
* A phone that writes `07 02` without the new parser will drop layout-2 `card_read` and `heartbeat`
  frames as "bad size" (counted in `RmNotifyGate.droppedCount`) and its reads would not be stored
  or acked. That is the reason for the opt-in. If it happens, `07 01` restores layout 1.
* A phone that ignores trailing bytes (the older lenient decoders) still reads every layout-1
  field from a layout-2 frame, because the new bytes are at the end.


---

## 8. Pairing, link level and the signing key

Everything here is enforced by `toms_core` (`pairing.h`, `authz.h`, `keyring.h`) and tested on the host
(`test_auth.c`). None of it has run on hardware.

### 8.1 Who may pair

| Rule | Detail |
| --- | --- |
| Pairing window | A new pairing is accepted only while the window is open. It opens when the **button is held at power-up** (sampled at the start of `main`, read twice 30 ms apart) and shows **PAIRING** with the seconds left on the LCD. It closes after `CONFIG_TOMS_PAIR_WINDOW_S` (default **60 s**) or as soon as a bond is made. A reset without the button opens nothing. |
| One bond | `CONFIG_BT_MAX_PAIRED=1`. While a bond exists, only that same central may pair again (even with the window open); any other is refused (`TOMS_PAIR_DENY_FULL`). To enrol a different phone, forget the bond with the button: section 8.7. |
| Authenticated pairing only | The central must announce an IO capability that can end in numeric comparison or a passkey: DisplayYesNo (1), KeyboardOnly (2) or KeyboardDisplay (4). DisplayOnly (0) and NoInputNoOutput (3) can only produce Just Works and are refused. With `CONFIG_BT_SMP_SC_ONLY=y` the stack also refuses legacy (non-Secure-Connections) pairing. |
| Refusal | `pairing_accept` returns `BT_SECURITY_ERR_PAIR_NOT_ALLOWED` (SMP "Pairing Not Supported") for all three. Reconnecting with the existing bond never needs the window. |

The 6-digit number is shown on the LCD, and the machine confirms its side of numeric comparison itself (as
before). The window plus the one-bond rule are what keep a stranger out; the user still compares the numbers.

### 8.2 Link level per diag command

"Authenticated link" = `bt_conn_get_security() >= CONFIG_TOMS_AUTH_LEVEL`. That is **4** (authenticated LE Secure
Connections, 128-bit key) in this build because Secure Connections Only is on; a build with
`CONFIG_BT_SMP_SC_ONLY=n` falls back to **3** (authenticated, legacy allowed). Level 2 never passes.
`CONFIG_TOMS_DEV_MODE` builds skip the check.

| cmd | what | link | other condition |
| --- | --- | --- | --- |
| `00` | open a diagnostic session | authenticated | none; bytes after `00` are ignored |
| `01`-`04` | buzzer, screen, backlight, NFC test | not link-gated (GATT still needs encryption) | an open session (`00`), the machine's local diagnostic mode, or a dev build |
| `05` | silence the alarm | authenticated | signature rule, 8.3 |
| `06` | store the first signing key | authenticated | none (once) |
| `07` | choose the message layout | authenticated | none |
| `08` | replace the signing key | authenticated | signed, 8.4 |

A refused command gets a reply with status byte `0xFF`: `[0x80,0,0,0xFF]` (00), `[0x85,0,0,0xFF]` (05),
`[0x86,0xFF,0,0]` (06), `[0x87,0xFF,version,2]` (07), `[0x88,0xFF,grace,0]` (08).

### 8.3 `05` signed (silence the alarm)

```
05 00 [ s0 s1 s2 s3 s4 s5 s6 s7 ]        2 bytes without a key, 10 bytes with the signature
```
`s0..s7` = the first 8 bytes of `HMAC-SHA256(key, 0x0E || 05 00)`: the usual trailer (section 6) with the diag
characteristic suffix `0x0E` and the two command bytes as the payload.

| Machine state | `05 00` unsigned | `05 00` + valid signature | `05 00` + wrong signature |
| --- | --- | --- | --- |
| no key yet | accepted (authenticated link) | accepted | refused |
| key present, normal build | **interim rule**: accepted only from a bonded central that has an open diagnostic session (`00`), and logged at WARN; otherwise refused | accepted | refused |
| key present, `CONFIG_TOMS_DEV_MODE` | accepted | accepted | refused (a wrong signature is refused in every build) |

The interim rule exists because the phone does not sign yet (a separate Android PR does). It is meant to be
removed once the phone signs: then an unsigned `05` is refused as soon as a key exists. A signature made with the
previous key during grace (8.4) is accepted too.

### 8.4 `08` replace the signing key

```
offset  size  field
0       1     08
1..32   32    new key
33..40  8     signature
total   41    (TOMS_KEY_REPLACE_LEN)
```
signature = the first 8 bytes of `HMAC-SHA256(current_key, 0x0E || 08 || new_key)`: the signed bytes are the
suffix `0x0E`, the command byte and the 32 key bytes (34 bytes).

Reply on the diag characteristic: `[0x88, status, grace, 0]`.

| status | meaning | key changed |
| --- | --- | --- |
| 0 | the new key is in force | yes |
| 1 | no key yet: provision with `06` first | no |
| 2 | not signed under the current key (or the grace key), or a partial / zero trailer | no |
| 3 | wrong length or wrong command byte | no |
| 4 | could not be saved to flash | no |
| 0xFF | the link was not authenticated enough | no |

`grace` = 1 when the previous key is still accepted.

**One-step grace.** A replace the phone never saw the reply to must not lock it out:
1. a successful replace puts the new key in force and keeps the old one as the grace key;
2. a value signed with the grace key still verifies, and so does a retried `08` signed with it (it overwrites
   the new key; the grace key stays the one the phone still has);
3. the first value that verifies under the **new** key ends the grace: the old key is forgotten and flash is
   updated;
4. another replace overwrites the grace key with the key it replaced, so only one previous key is ever kept.

The machine signs everything it sends with the key in force. Order for the phone: send `08`, keep the old key
until a signed notify from the machine verifies under the new key, then forget the old one.

The keys are stored in the settings partition as one record (`toms/k/keys`, 67 bytes: flags, key in force,
previous key, CRC-16). The earlier single-key record is read as the key in force on first boot with this
firmware and is replaced the first time the key changes. Nothing key-shaped is in the source or the image.


### 8.5 Every command the diagnostic app sends today, against these rules

From `toms_android/ota` (branch `v2/return-machine-fw`): `OtaViewModel.kt` and `BleTransport.kt`.

| App action | Bytes | Where in the app | Today's firmware | After this change |
| --- | --- | --- | --- | --- |
| connect, pair | `createBond()`, numeric comparison | BleTransport.kt:169-172 | any central pairs | pairs only in the window, as DisplayYesNo/KeyboardDisplay, one bond |
| unlock on connect | `00` + `TOMS!` | BleTransport.kt:260, OtaViewModel.kt:323 | session opens on an L2 link | opens on an authenticated link; the five bytes are ignored |
| buzzer test | `01 <tone>` | OtaViewModel.kt:462 | needs a session | unchanged: needs the session `00` opened |
| next screen | `02` | OtaViewModel.kt:470 | needs a session | unchanged |
| backlight | `03` | OtaViewModel.kt:478 | needs a session | unchanged |
| PN532 check | `04` | OtaViewModel.kt:490 | needs a session | unchanged |
| silence alarm | `05 00` | BleTransport.kt:275, OtaViewModel.kt:439 | accepted unconditionally | no key: accepted; key present: accepted unsigned only as a bonded central with an open session (the app has both), logged |

The app never sends `06`, `07` or `08`, so those three do not affect it. The unsigned `05` keeps working, with a
log line, until the app signs it.

### 8.6 What the phone must change (Android; not part of this change)

1. **Authenticated pairing.** Pair with numeric comparison (`createBond()` on a device that announces DisplayYesNo
   or KeyboardDisplay); a Just Works pairing is refused. Needs LE Secure Connections (every phone since
   Android 6). The user must hold the machine's button while powering it up, then pair within 60 s.
2. **Sign `05`.** Append the 8-byte trailer of 8.3 once the machine has a key (`device_info.flags` bit 1).
3. **Key replace.** Send `08` (8.4), wait for `88 00 ..`, keep the old key until a notify from the machine
   verifies under the new one, then drop it. Handle statuses 1 to 4 and 0xFF.
4. **Re-pairing after the bond was cleared on the machine** (8.7): the phone still holds the old pairing, so a
   connection fails on encryption. Remove the pairing in Android (Settings > Bluetooth > TOMS-RM > Forget) and
   pair again inside the window. The signing key is kept, so nothing about the key needs redoing.

### 8.7 Forgetting the bond (hold the button through the window)

The one bond can be forgotten at the machine, without erasing the signing key:

1. Hold the button while powering the machine up. The pairing window opens (8.1) and the LCD shows **PAIRING**.
2. **Keep holding for 5 s**, counted continuously from the power-up sample (the count starts when `main()` has
   read the button, early in boot, so do not let go before 5 s have passed).
3. The LCD shows **BOND CLEARED** (and a short beep) for 2 s. The stored bond is deleted, the pairing window
   is closed while this shows, and then a **fresh 60 s window** opens (the LCD goes back to **PAIRING**).
4. Any central that can do an authenticated pairing may now take the one slot, the previous phone included.

What is cleared and what is not:

| Item | After the hold |
| --- | --- |
| The bond (long-term key, identity, the central's subscriptions `bt/ccc`) | **deleted** (`bt_unpair`, all bonds, the one slot) |
| A central connected at that moment | disconnected by the stack |
| The signing key ring (`toms/k/keys`, key in force and any grace key) | **kept**, never read or written by this path |
| The read log, its sequence counter and the drop counter | kept |
| The layout choice (`07`) | per connection: back to layout 1 on the next connect, as always |

Rules, all in `toms/bondclr.h` and tested on the host:
* Only a hold that **starts at power-up** counts. A button pressed later never starts the count.
* Releasing for `TOMS_BONDCLR_RELEASE_MS` (150 ms) or longer **gives up for good** until the next power-up, and
  so does a release the sampler could not see (a gap of more than `TOMS_BONDCLR_MAX_GAP_MS` = 500 ms between
  samples, or a clock that goes backwards). A bounce shorter than 150 ms is forgiven.
* The count is measured with differences of the 32-bit millisecond clock, so it is correct across the wrap.
* It fires once, at the first sample at or after 5 s. While it is counting, the normal button actions are
  ignored. If Bluetooth is not up within 10 s of that moment, or `bt_unpair` fails, the bond is **not**
  cleared and the machine logs why.
* A reset without the button, an OTA reboot or a watchdog reset never clears anything.

**What a phone sees afterwards** (from reading the stack's and the apps' code; none of it was run):
* The machine now has no record of the phone. The phone still has its half of the pairing. When the phone
  reconnects, the machine cannot find the long-term key and refuses encryption ("PIN or key missing"), so
  the link drops before any diag or data traffic. No characteristic is readable or writable that needs
  encryption (every characteristic except the open read of `device_info`).
* **Conductor app** (`AndroidBleReturnMachineLink`): it calls `createBond()` only when Android says the
  device is *not* bonded, and Android still says it is. So it will keep failing and retrying until the user
  removes the pairing in Android settings (Forget) and the phone reconnects.
* **Diagnostic app** (`ph.toms.ota`, `BleTransport.connect`): it recognises a link that dies on encryption
  with a bond held, removes the stale pairing itself and pairs again, once. That needs the window to be
  open at that moment (hold the button through the 5 s, then connect within 60 s), otherwise the pairing
  is refused with SMP "Unspecified Reason" (0x08) and it reports "pairing failed or was refused".
* After re-pairing: layout 1 again, `time_sync` and the state table are sent again as on every connect, the
  notify subscriptions are made again (the stored ones were deleted), and **signed notifies still verify**,
  because the key was kept: no new `06` and no `08` are needed. A phone that never held the key still
  cannot get one through `06` (the key exists), it needs `08` signed under the current key.
* Stored reads in the flash log are still there and are offered again after the next connection.
