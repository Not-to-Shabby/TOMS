# Return Machine — BLE protocol spec (draft)

Status: behavior comes from the plan doc (`documentation/toms-v2-plan-clean.md`,
"Return Machine" section) and the firmware brief. **The concrete UUIDs, byte layouts and
constants below are firmware-side choices made for this repo** — they were not spelled out
in the plan doc. Reconcile before the Phase 1.5 simulator (app side) and the Android BLE
client are written; both must implement exactly this document.

Terminology: "Return Machine" (RM), "passenger card". Never "return terminal" or "seat card".
The card is treated as a UID only; whether it stores a balance is UNCONFIRMED and irrelevant
to this protocol. Android has no NFC-DEP; everything here is ISO 14443-A reader mode.

## Transport

- BLE 4.2+ peripheral, one connection at a time. The conductor's phone is the central and the
  authority for card state.
- Security: LE Secure Connections, bonded pairing at enrollment. The phone that enrolls the
  machine is the only central that may write; unsigned writes from other centrals are ignored.
- Every notify carries a truncated HMAC-SHA256 signature (8 bytes) keyed by the per-machine
  key (32 bytes, generated at enrollment, stored in the RM's NVS and in the phone's
  Keystore-encrypted config). Key material never enters git.
- MTU: request 247; message fits one characteristic value. ATT timeout default.
- Byte order: little-endian. Multi-byte integers are unsigned unless stated.

## GATT layout

Service UUID: `544F4D53-5245-5455-524E-414348494E45` ("TOMS RETURN MACHINE").
Characteristics share the base with a 1-byte suffix:

| Suffix | Name | Properties | Direction |
| --- | --- | --- | --- |
| `…01` | card_read | notify, read | RM → phone |
| `…02` | state_update | write, write-without-response | phone → RM |
| `…03` | paid_check | write, write-without-response | RM → phone |
| `…04` | paid_reply | notify | phone → RM |
| `…05` | heartbeat | notify | RM → phone |
| `…06` | time_sync | write | phone → RM |
| `…07` | config | write | phone → RM |
| `…08` | ack | write | phone → RM |

The Device Name characteristic carries `TOMS-RM-<last 4 hex of the machine id>`; the
appearance is Generic. Advertising: connectable, 1 s interval while disconnected, service
UUID in the advertise payload.

## Messages

Common trailer on every characteristic **value sent by the RM**:
`sig[8]` = HMAC-SHA256(machine_key, all preceding bytes of the value), truncated to 8 bytes.
Writes from the phone to `state_update`, `time_sync`, `config` and `ack` carry the same
trailer; the RM drops the write if the signature or the anti-replay counter fails.

### card_read (RM → phone, notify)

| Field | Size | Notes |
| --- | --- | --- |
| read_seq | u32 | Monotonic per machine, persists across reboots (stored with the buffer). |
| timestamp | u32 | RM clock, UTC epoch seconds, set by time_sync. 0 if never synced. |
| boot_ms | u32 | ms since boot (for skew diagnosis when timestamp is 0). |
| uid_len | u8 | 4, 7 or 10 (ISO 14443-A UID length). |
| uid | uid_len | First-appearance UID bytes as read by the PN532 (no reversal). |
| atype | u8 | 1 = NTAG/Type 2, 2 = DESFire/Type 4, 3 = MIFARE Classic, 0 = other/unknown. |
| state_cached | u8 | The RM's cached state for this UID (same encoding as state_update), 0xFF if unknown. |
| sig | 8 | |

The RM always notifies card_read on a physical read (deduplicated: the same UID is not
re-notified within 2 s), then, if connected, follows with a paid_check.

### state_update (phone → RM, write)

| Field | Size | Notes |
| --- | --- | --- |
| table_version | u32 | Increments on every change the phone makes. |
| flags | u8 | bit 0 set = full snapshot (clear = delta). |
| count | u8 | Entries following. |
| per entry: uid_len u8, uid, status u8 | | |

Status codes: 0 available, 1 assigned_unpaid, 2 assigned_paid, 3 returned,
4 returned_unpaid, 5 lost.

On receipt the RM applies entries to its cached table, stores `table_version`, and answers
with ack (below). On reconnect the phone always sends a full snapshot first.

### paid_check (RM → phone, write) and paid_reply (phone → RM, notify)

Request: `check_seq u32`, `uid_len u8`, `uid`.
Reply: `check_seq u32`, `verdict u8`.

Verdicts: 0 OK (card is assigned_paid — play the paid tone), 1 UNPAID (play the unpaid
alarm; the phone shows the collector), 2 UNKNOWN (UID not in the phone's registry — play the
unknown tone), 3 ALREADY_RETURNED (card read twice at exit — play the duplicate tone).

The RM waits `paid_timeout_ms` (config, default 1500, brief allows 1–2 s). If BLE is down,
the phone is not bonded, or no reply arrives in time, the RM falls back to its cached state:
cached assigned_paid → paid tone; cached returned_unpaid → alarm tone; anything else,
including unknown → the distinct **unverified** tone (not the full alarm).

### heartbeat (RM → phone, notify)

| Field | Size | Notes |
| --- | --- | --- |
| uptime_s | u32 | |
| battery_pct | u8 | 0–100, 0xFF = not measurable (no divider/gauge installed yet). |
| battery_mv | u16 | Raw cell millivolts, 0 = not measurable. |
| nfc_status | u8 | 0 ok, 1 no answer on bus, 2 firmware-version error, 3 init failed. |
| state_version | u32 | Last applied state table version. |
| buffered_reads | u16 | Reads queued in flash, not yet acked by the phone. |
| reset_reason | u8 | Zephyr `SYS_RESET_REASONS` mapped: 0 power-on, 1 brownout, 2 watchdog, 3 software, 4 pin/debug, 5 other. |
| sig | 8 | |

Interval: config, default 10 s. The phone treats a missed heartbeat window as RM offline
and warns the conductor (phone-side rule from the plan doc).

### time_sync (phone → RM, write)

`epoch u32` (UTC seconds), `remaining_ms u16` (sub-second remainder). The RM sets its clock
and keeps the boot-offset so timestamps survive until the next sync.

### config (phone → RM, write)

| Field | Size | Default |
| --- | --- | --- |
| vehicle_id_len + vehicle_id | u8 + bytes | empty |
| tones_mask | u8 | 0xFF (all tones enabled) |
| paid_timeout_ms | u16 | 1500 |
| heartbeat_interval_s | u16 | 10 |
| alarm_clear_policy | u8 | 0 (alarm tone repeats until a state_update clears the card) |

Write persists to NVS. The vehicle binding written at enrollment locks the RM to that
phone's bond.

### ack (phone → RM, write)

| Field | Size | Notes |
| --- | --- | --- |
| kind | u8 | 1 = state ack, 2 = read ack |
| value | u32 | kind 1: applied table_version. kind 2: highest read_seq fully received by the phone. |

kind 2 drives the flash ring buffer flush: on reconnect the RM sends buffered card_reads in
read_seq order and only erases entries the ack has covered.

## Reconnect sequence

1. Phone connects, bonds are verified, phone sends a full state_update snapshot.
2. RM applies it, acks kind 1 with the version.
3. Phone acks kind 2 with the last read_seq it has; the RM notifies the buffered card_reads
   above that watermark in order (one unacked window of 8 at a time).
4. RM starts the heartbeat; normal operation begins.

## Tone patterns (fixed pitch, patterns only)

| Event | Pattern |
| --- | --- |
| Card read OK / paid | 1 × 80 ms beep |
| Unpaid at exit | 3 × 200 ms beeps, 150 ms gaps |
| Unknown card | 2 × 300 ms beeps, 100 ms gaps |
| Unverified (BLE down or late) | 2 × 60 ms short beeps |
| Duplicate read | 1 × 400 ms beep |
| Boot / reconnect | rising 3 quick beeps (30 ms) |

## Open questions for reconciliation

1. UUID base: the plan doc gives no UUIDs; the TOMS-ASCII base above is a proposal. If the
   Phase 1.5 simulator or the Android client used different ones, this document is updated,
   never the code silently.
2. Signature: 8-byte truncated HMAC-SHA256 vs full 16/32 bytes — 8 bytes chosen for MTU headroom.
3. Whether the `ack` characteristic (a firmware-side addition) conflicts with anything the
   plan doc's simulator work assumed.
4. Key provisioning path: enrollment writes config + key over the bond, or the key is
   provisioned by a bench script (`firmware/tools/key_provision.py`) before the vehicle bind.
   Decide at enrollment design time; until then the RM runs with a development key only when
   `CONFIG_TOMS_DEV_MODE=y`, never in a field build.
