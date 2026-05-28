# TOMS — Final Implementation Plan
### Informed by: Survey Synthesis Report (n=20) + SLR Chapter II (22 Primary Studies)

> [!IMPORTANT]
> **TOMS = Transportation *Occupancy* Monitoring System.**
> Occupancy is the primary output. Every other feature — fare calc, GPS, alarms, dashboard — exists to make occupancy data accurate and automatic.

---

## I. SLR Literature Analysis (TOMS.txt — Chapter II)

This section maps the 22-study Systematic Literature Review (PRISMA, 2020–2026) directly to TOMS design decisions, and explicitly verifies where TOMS aligns with or improves upon the literature.

---

### A. Literature Challenges & TOMS Solutions

#### Challenge 1 — Inaccurate Real-Time Passenger Manifests
> *"Basic IR and ultrasonic sensors struggle with precision when passengers move in close proximity at the doorway" (Haque et al., 2024; Murdan et al., 2020)*

**TOMS approach:** Avoids sensor-based entry/exit detection entirely. Instead, **NFC tap = boarding event**. The conductor physically assigns a slave device to each passenger — this is the manifest. No false positives, no crowding errors. 100% intentional-tap accuracy, matching S21's dual-verification outcome without the fixed-gate infrastructure.

> **Alignment:** Surpasses IR/ultrasonic approaches from S3, S4, S14. Matches the "reusable seat-marker" gap identified in Section 2.3.3.

---

#### Challenge 2 — Connectivity and Synchronization in Dead Zones
> *"Most standard IoT architectures fail to synchronize data effectively when a vehicle enters a cellular signal dead zone" (Kulange et al., 2025; Nirmala et al., 2024)*

**TOMS approach — Real-time first, cache as fallback:**

```
Event occurs (NFC tap / payment / alarm)
        │
        ▼
  Internet available?
    YES ──► POST to server immediately (real-time occupancy)
    NO  ──► Write to local SQLite queue (synced = 0)
                    │
                    ▼
          Internet restored?
            YES ──► Flush SQLite queue (oldest first) + continue real-time
            NO  ──► Keep caching, retry every 30s
```

- **ESP-NOW** (2.4 GHz P2P) — always works between master and slaves, internet irrelevant
- **Real-time path:** HTTP POST each event to server as it happens (when signal available)
- **Cache path:** SQLite `passenger_logs` with `synced = 0` flag — already in the schema
- **Auto-flush:** `SyncService` watches connectivity, drains queue when internet returns, sends all cached + marks `synced = 1`
- **SPIFFS on master** — secondary backup if phone itself disconnects from master
- **Result:** Dashboard is live when signal exists; catches up automatically when it returns

> **Alignment:** Extends S17 (Kulange) offline-first with real-time push layer. Matches S22 (Vadivel) cloud-based monitoring goal. Directly addresses dead zones at Tag-ibo, Sta. Fe, Barinaut, Timoga.

---

#### Challenge 3 — Portability and Hardware Robustness
> *"Many designs are structurally intended for large, stationary bus terminals rather than compact, mobile units" (Bhavani et al., 2025)*

**TOMS approach:** The master is a handheld ESP32-S3 device carried by the conductor. Slave devices are small battery-powered units passed to passengers. No fixed infrastructure required. Matches "portable handheld POS" gap from Section 2.3.3.

> **Alignment:** Directly addresses the research gap: *"a unified handheld POS that integrates automatic passenger counting... features currently confined to fixed gate infrastructure."*

---

#### Challenge 4 — High Initial Cost and Operator Resistance
> *"Small-scale operators are often resistant to upfront costs, preferring traditional manual collection" (Murdan et al., 2020; Zaman et al., 2023)*

**TOMS approach:**
- Uses ESP32 (weighted score **4.70** in Table 2.10 — highest of all platforms evaluated)
- ESP32 chosen for: dual-core 240MHz, native Wi-Fi/BT, low power, low cost (~$5/unit)
- No proprietary hardware, no cloud subscription required for basic operation
- Reusable slave devices replace single-use paper tickets — **cost reduces over time**

> **Alignment:** ESP32 selection matches the literature's consensus (S7, S14, S15, S17, S18, S19, S21 all use ESP32). Dashboard Option A (static JSON) requires zero server costs.

---

#### Challenge 5 — Data Security and Fare Transparency
> *"RFID card cloning, unauthorized database access, and fraudulent transactions remain concerns" (Iswarya et al., 2022; Kulange et al., 2025)*

**TOMS approach:**
- **eFuse MAC as UID** — hardware-burned, impossible to clone without physical access
- **AES-256 encryption** on ESP-NOW payloads (`toms_crypto` module, already implemented)
- **Anti-fraud UID validation** in slave firmware: board commands with wrong `target_uid` are silently dropped
- **Immutable SQLite log** on phone: each tap event timestamped and stored permanently
- **QR receipt** on slave screen: passenger has photographic proof

> **Alignment:** Matches S16's "Device ID Lock," S18's offline anti-malpractice storage, and S21's dual-verification fraud prevention.

---

### B. Literature Research Gaps & TOMS Responses

| Gap Identified in Literature | TOMS Solution |
|---|---|
| No unified handheld POS combining fare + counting | Master device = POS + NFC tap = count. Single unit. |
| Reusable seat-based markers (not personal cards) | Slave devices ARE the reusable markers. Returned after use. |
| Offline-first logic for dead zones | ESP-NOW + SQLite + SPIFFS. Zero cloud dependency during transit. |
| Solutions tailored for "konduktor-led" PUV ops | Entire workflow designed around conductor's actual process. |
| Low-cost open-source for local minibus ops | ESP32 (~$5), open firmware, no licensing fees. |

---

### C. Occupancy — Does TOMS Align with the Literature?

**YES — and it fills the gap the literature couldn't.**

The SLR found that occupancy monitoring in existing systems uses:
- **IR/ultrasonic sensors** at doorways (S3, S4, S14) — error-prone in crowded boarding
- **ESP32-CAM + FOMO ML** (S19, Gonzales et al.) — 95.3% seat precision, but requires camera hardware
- **Optical-RFID dual-verification** (S21, Lam et al.) — 100% accuracy but fixed gate infrastructure only

**The gap the literature explicitly names (Section 2.3.3):**
> *"A significant void exists regarding reusable, seat-based RFID markers which are more practical for high-turnover public utility vehicles than individual smart card ownership."*

**TOMS fills this gap exactly:**

| Literature Method | TOMS Method |
|---|---|
| IR sensor counts everyone through a door | NFC tap assigns a device — only boarded passengers are counted |
| Camera detects if seat is empty/occupied | Slave device state (ACTIVE/PAID) IS the occupancy state |
| Fixed gate dual-verification | Mobile: conductor taps slave → automatic boarding record |
| Personal RFID card owned by passenger | Reusable slave device — owned by operator, returned each trip |
| Seat occupancy as a count | Occupancy = `slavesDeployed / maxCapacity` (real-time, per-bus) |

**The TOMS occupancy model is not just aligned — it represents the specific solution the literature calls for but no prior study implemented.** The slave device is simultaneously: the reusable marker, the receipt terminal, the payment button, and the occupancy counter.

---

### D. Hardware Alignment with Literature (Table 2.9 / 2.10)

| Criterion | Literature Winner | TOMS Choice | Score |
|---|---|---|---|
| Processing Power | ESP32 | ESP32-S3 (faster) | ✅ |
| IoT Connectivity | ESP32 | ESP32-S3 (Wi-Fi + BT + ESP-NOW) | ✅ |
| Power Efficiency | ESP32 | ESP32-S3 (deep sleep on slave) | ✅ |
| I/O & Memory | ESP32 | ESP32-S3 (more GPIO, PSRAM) | ✅ |
| Cost & Scalability | Arduino (cheapest) | ESP32-S3 (slightly higher but justified) | ✅ |

---

## II. Survey Problem Analysis & TOMS Solutions

The following maps each of the 7 identified field problems directly to TOMS features.

---

### Problem 1 — Inaccurate Passenger Counting
> *80% of conductors rely solely on memory or visual estimation*

**Root cause:** No physical record of boarding. A passenger could board, ride, and alight without being counted if the conductor is busy with another passenger.

**TOMS Solution:**
- Every passenger is assigned a **slave device** via NFC tap — this is the count. If no tap, no count, no ride.
- The master stores a `PASSENGER_BOARD` ESP-NOW event for each tap, logged to SPIFFS immediately.
- The conductor's phone shows a **real-time passenger count** in the active sessions panel — visible at a glance even during peak hours.
- The company dashboard shows ridership per trip, per route, per time-of-day — replacing memory with data.

---

### Problem 2 — Fare and Change Computation Errors
> *85–90% agree that managing exact change and fare tracking is a daily struggle*

**Root cause:** Conductors compute fares mentally while multitasking. Large bills create change-management stress.

**TOMS Solution:**
- Fare is **auto-calculated by the app** (zone-based stop pair → fare dict lookup). Conductor selects origin/destination → fare appears instantly. Zero mental math.
- The slave device **displays the fare on screen** — passenger sees the exact amount. No verbal disagreement possible.
- Discount types (Regular / Student / PWD / Senior) are selected in the app — discount is applied automatically, shown on receipt.
- **Change computation helper:** When conductor taps "collect payment", the app shows a change calculator: enter tendered amount → change displayed. Optional feature for Phase 2.

---

### Problem 3 — Cash-to-Passenger Count Mismatch
> *80% confirm daily discrepancy between cash collected and passenger count*

**Root cause:** Paper tickets can be lost or skipped. No link between physical ticket and collected cash.

**TOMS Solution:**
- Every transaction generates a **digital log entry** (timestamp, fare, slave UID, route, passenger type) stored locally in SQLite on the phone.
- The **company dashboard** receives these logs via USB sync or Wi-Fi when the bus returns to terminal — showing exact expected revenue vs actual per trip.
- End-of-day reconciliation is no longer manual: TOMS generates an automatic **shift report** with total passengers, total expected revenue, and breakdown by passenger type.

---

### Problem 4 — Disputed Transactions with No Audit Trail
> *2–3 disputes per conductor per week; no objective resolution possible*

**Root cause:** Paper tickets can be discarded. Verbal-only transactions leave no evidence.

**TOMS Solution:**
- Every `PASSENGER_BOARD` event is stored with: timestamp, slave UID, fare, route, seat number, boarding stop.
- The slave device generates a **QR receipt** — passenger can photograph it as proof.
- The company dashboard has a **transaction search** — any disputed fare can be looked up by time range or UID.
- The slave device shows the receipt on screen until the passenger dismisses it — no physical ticket to lose.

---

### Problem 5 — Environmental Waste from Paper Tickets
> *90% agree paper tickets produce litter*

**Root cause:** Single-use paper tickets issued per passenger per trip.

**TOMS Solution:**
- Slave devices **are the ticket** — reusable, returned to the conductor after alighting.
- No paper is produced. Receipt is digital (QR on screen).
- The company owns a pool of slave devices shared across trips.
- **Environmental impact quantifiable:** TOMS can report "paper tickets replaced" count on the dashboard — useful for thesis and reporting.

---

### Problem 6 — Offline-Only Operational Environment
> *80% report dead zones: Tag-ibo, Sta. Fe, Barinaut (Dalipuga); Timoga (Buru-un)*

**Root cause:** Routes pass through forested/rural areas with zero cellular signal.

**TOMS Solution (already designed for this):**
- **ESP-NOW** — no internet, no Wi-Fi infrastructure needed. 2.4 GHz peer-to-peer between master and slaves, works anywhere.
- **NFC** — short-range, completely offline.
- **SQLite on phone** — all transactions stored locally. No cloud write during operation.
- **SPIFFS on master** — backup log storage if phone disconnects.
- **Dashboard sync:** When the bus returns to terminal (Wi-Fi available), the app uploads all pending logs automatically. The dashboard shows a "pending sync" badge until this completes.
- The system is **100% functional with zero network** during route operation.

---

### Problem 7 — Manual End-of-Day Reconciliation
> *DONSALS: office staff travels to each bus at 6 PM to collect tickets and cash*

**Root cause:** No digital record. Physical ticket collection is the only audit mechanism.

**TOMS Solution:**
- At end of shift, conductor connects phone to terminal Wi-Fi (or plugs into office PC via USB).
- App auto-uploads all unsynced logs to the company server.
- Company dashboard instantly shows the shift summary: passengers, revenue, route, discounts, unpaid fares.
- **The 6 PM office visit becomes unnecessary.** Reconciliation is done in seconds from any browser.
- Office staff can review each bus's data remotely in real time when any Wi-Fi is available.

---

## II. Confirmed Architecture Decisions

| Decision | Confirmed Choice |
|----------|-----------------|
| GPS source | Phone GPS (Option A) |
| Session management | Flutter AppState / phone (Option A) |
| Fare calculation | Zone-based stop pairs (Option B) + GPS auto-detects boarding point A |
| Discounts | Regular / Student / PWD / Senior Citizen |
| Boarding UI | Both sequential (quick) + queue (multi-passenger) |
| Proximity alarm | Phone GPS geofencing, 200m threshold (Option A) |
| Offline operation | 100% — ESP-NOW + SQLite + SPIFFS, sync when Wi-Fi available |
| Firmware changes | Minimal (Phase 4 only: slave alarm command) |
| **Occupancy model** | **No fixed seats. Occupancy = slaves currently deployed ÷ bus max capacity. Slot number (1, 2, 3…) assigned dynamically when slave is handed to a passenger.** |

---

## III. System Components

```
┌──────────────────────────────────────────────────────────────┐
│              COMPANY DASHBOARD (Web — browser)               │
│  Real-time fleet view, shift reports, audit trail, analytics │
│  Served from: Node.js or simple Python backend on office PC  │
│  Or: Static JSON export viewed in a local web app            │
└───────────────────────┬──────────────────────────────────────┘
                        │ Wi-Fi / USB sync (end of shift)
┌───────────────────────▼──────────────────────────────────────┐
│              FLUTTER APP (Conductor's Phone)                  │
│  Session mgmt • GPS • Fare calc • Proximity alarm            │
│  Offline SQLite log • Sync service                           │
└───────────────────────┬──────────────────────────────────────┘
                        │ USB CDC (always connected in-trip)
┌───────────────────────▼──────────────────────────────────────┐
│              ESP32 MASTER                                     │
│  NFC-DEP initiator (PN532) • ESP-NOW hub • SPIFFS backup    │
└────────────┬──────────────────────────────┬──────────────────┘
       NFC tap                          ESP-NOW
┌───────────▼──────────┐    ┌────────────▼──────────────────┐
│  SLAVE (NFC Target)  │    │  SLAVE (Button Press / Board) │
│  Shows QR receipt    │    │  PASSENGER_BOARD confirm      │
└──────────────────────┘    └───────────────────────────────┘
```

---

## IV. Occupancy Model

Minibuses (jeepneys) do **not** have assigned seat numbers. Passengers sit anywhere available. Occupancy is therefore defined by:

- **`maxCapacity`** — the physical maximum passengers the bus can hold (configured once per bus, e.g. 20). Stored in `assets/vehicles.json`.
- **`slavesDeployed`** — number of slave devices currently handed out and active in this trip.
- **`occupancyRate`** = `slavesDeployed / maxCapacity`

Each slave gets a **dynamic slot number** (1, 2, 3…) assigned in the order the conductor hands it out — not tied to any physical seat. This slot number is what appears on the receipt and in the app as a short passenger identifier.

### Slot Number vs Seat Number

| Old (wrong) | New (correct) |
|---|---|
| `seat_number` = fixed physical seat | `slot_number` = Nth passenger boarded this trip |
| Stored in slave NVS | Assigned by master at BOARD_COMMAND time |
| E.g. "Seat 3" always means row 1 right | E.g. "#3" means the 3rd passenger who boarded |

The `seat_number` field in `toms_board_command_t` is **reused** as `slot_number`. No firmware struct change needed — only the semantics change.

### Passenger States
```
ACTIVE    → BOARD_COMMAND sent, receipt on slave screen, payment pending
PAID      → passenger pressed button, fare collected, slave returned
ALARMING  → proximity alarm triggered, still unpaid
```

### Occupancy Flow
```
Trip starts. Bus has 0 slaves deployed. Capacity = 20.

Passenger 1 boards:
    Conductor fills BoardingSheet (dest + type)
    → Assigns slot_number = 1 (auto-incremented)
    → Taps slave via NFC
    → BOARD_COMMAND: slot=1, fare=₱13.00, dest=Country Hills
    → Slave shows: "#1 · Country Hills · ₱13.00"
    → App: slavesDeployed = 1  →  Occupancy: 1/20 (5%)

Passenger 2 boards:
    → slot_number = 2
    → slavesDeployed = 2  →  Occupancy: 2/20 (10%)

Passenger 1 presses button (ready to pay):
    → BUTTON_PRESS ESP-NOW → master → USB "button_press"
    → App matches UID → marks slot 1 as PAID
    → Conductor collects fare, retrieves slave
    → slavesDeployed stays 2 until slave is re-assigned
    → App: 1 paid, 1 unpaid

GPS within 200m of Country Hills:
    → Slot 2 still unpaid → state = ALARMING
    → App banner + vibration
```

### `PassengerSlotState` enum (Flutter)
```dart
enum PassengerSlotState { active, paid, alarming }

extension PassengerSlotStateX on PassengerSlotState {
  Color get color => [
    const Color(0xFFFF9F43),   // active   → orange
    const Color(0xFF2ED573),   // paid     → green
    const Color(0xFFFF6B6B),   // alarming → red
  ][index];
  String get label => ['Unpaid', 'Paid', 'ALARM'][index];
}
```

### `OccupancySnapshot` (for dashboard sync)
```dart
class OccupancySnapshot {
  final String vehicleId;
  final DateTime timestamp;
  final int maxCapacity;          // configured max (e.g. 20)
  final int slavesDeployed;       // currently handed out
  final int paidCount;
  final int unpaidCount;
  final int alarmingCount;
  double get occupancyRate => slavesDeployed / maxCapacity;
  final List<PassengerSlot> slots;
}

class PassengerSlot {
  final int slotNumber;           // 1-based, order boarded
  final String slaveUid;          // eFuse MAC
  final PassengerSlotState state;
  final String destination;
  final int fareCentavos;
  final PassengerType passengerType;
  final DateTime boardedAt;
}
```

---

## V. Data Models

### `PassengerType` enum
```dart
enum PassengerType { regular, student, pwd, senior }

extension PassengerTypeX on PassengerType {
  String get label => ['Regular', 'Student', 'PWD', 'Senior'][index];
  double get multiplier => [1.0, 0.80, 0.80, 0.80][index];
}
```

### `TransitStop`
```dart
class TransitStop {
  final int id;
  final String name;
  final double lat;
  final double lon;
}
```
Loaded from `assets/stops.json` at startup.

### `PassengerSession`
```dart
class PassengerSession {
  final String id;              // UUID
  final String slaveUid;        // eFuse MAC of assigned slave
  final TransitStop boarding;
  final TransitStop destination;
  final int fareId;
  final int baseFareCentavos;
  final int finalFareCentavos;  // after discount
  final PassengerType type;
  final DateTime boardedAt;
  bool paid;
  bool alarmTriggered;
}
```

### `PendingPassenger` (Queue mode)
```dart
class PendingPassenger {
  final String id;
  final TransitStop boarding;
  final TransitStop destination;
  final int fareId;
  final int fareCentavos;
  final PassengerType type;
  final DateTime queuedAt;
}
```

### `PassengerLog` (updated)
```dart
// New fields added:
final int passengerType;        // PassengerType.index
final int discountCentavos;
final String boardingStop;
final String destinationStop;
final double destinationLat;
final double destinationLon;
```

### `DeviceStatus` (updated)
```dart
// Rename: uartState → nfcState
// Parse: json['nfc_state'] instead of json['uart_state']
final int nfcState;
```

### `ShiftReport` (new — for dashboard sync)
```dart
class ShiftReport {
  final String vehicleId;
  final String routeName;
  final DateTime shiftStart;
  final DateTime shiftEnd;
  final int totalPassengers;
  final int totalRevenueCentavos;
  final int regularCount;
  final int studentCount;
  final int pwdCount;
  final int seniorCount;
  final int discountTotalCentavos;
  final List<PassengerLog> transactions;
}
```

---

## V. Database Schema

**Version bump: `v1` → `v2`**

```sql
-- Migration from v1 to v2
ALTER TABLE passenger_logs ADD COLUMN passenger_type    INTEGER DEFAULT 0;
ALTER TABLE passenger_logs ADD COLUMN discount_centavos INTEGER DEFAULT 0;
ALTER TABLE passenger_logs ADD COLUMN boarding_stop     TEXT    DEFAULT '';
ALTER TABLE passenger_logs ADD COLUMN destination_stop  TEXT    DEFAULT '';
ALTER TABLE passenger_logs ADD COLUMN destination_lat   REAL    DEFAULT 0;
ALTER TABLE passenger_logs ADD COLUMN destination_lon   REAL    DEFAULT 0;
```

---

## VI. New Assets

### `assets/stops.json`
```json
[
  {"id":1, "name":"DPWH - Tambo Terminal",       "lat":8.2287, "lon":124.2451},
  {"id":2, "name":"Country Hills",               "lat":8.2456, "lon":124.2611},
  {"id":3, "name":"Brgy. Burul",                 "lat":8.2378, "lon":124.2534},
  {"id":4, "name":"Tag-ibo",                     "lat":8.1912, "lon":124.2133},
  {"id":5, "name":"Dalipuga Terminal",            "lat":8.1756, "lon":124.2089}
]
```
Operator populates the actual coordinates before deployment.

---

## VII. Services (Flutter)

| Service | Role |
|---------|------|
| `StopService` | Loads stops.json, finds nearest stop to GPS coords |
| `SessionService` | Active sessions + pending queue + fare calc + discount |
| `GpsService` | Streams phone position every 5s |
| `ProximityService` | Monitors sessions, fires alarm at < 200m to destination |
| `SyncService` | **Real-time POST when online; SQLite cache queue when offline; auto-flush on reconnect** |
| `ConnectivityService` | Watches internet state (via `connectivity_plus`), notifies `SyncService` |
| `UsbService` | (Modified) NFC events + button_press → session markPaid |
| `AppState` | Orchestrates all services |

### `SyncService` — Detailed Behaviour

```dart
class SyncService {
  // Called after every boarding event, payment, or alarm
  Future<void> push(OccupancyEvent event) async {
    // 1. Save to SQLite immediately (synced = 0)
    await _db.insertEvent(event);

    // 2. If online — try to POST now
    if (_connectivity.isOnline) {
      await _trySendNow(event);
    }
    // If offline — leave in queue, ConnectivityService will flush
  }

  // Called by ConnectivityService when internet is restored
  Future<void> flushQueue() async {
    final pending = await _db.getUnsynced();   // synced = 0, ordered ASC
    for (final event in pending) {
      final ok = await _trySendNow(event);
      if (!ok) break;                          // stop on failure, retry next cycle
    }
  }

  // Retry every 30 seconds while offline
  void startRetryTimer() {
    Timer.periodic(Duration(seconds: 30), (_) {
      if (_connectivity.isOnline) flushQueue();
    });
  }
}
```

**What is pushed to the server on each event:**
```json
{
  "event_type": "board",          // board | payment | alarm | release
  "timestamp": "2026-05-26T21:05:33+08:00",
  "vehicle_id": "CDB-001",
  "route": "Dalipuga",
  "slot_number": 3,
  "slave_uid": "A1B2C3D4E5F6",
  "passenger_type": "senior",
  "fare_centavos": 1040,
  "discount_centavos": 260,
  "boarding_stop": "DPWH Terminal",
  "destination_stop": "Country Hills",
  "occupancy_now": 14,             // slavesDeployed at time of event
  "max_capacity": 20
}
```

This payload lets the dashboard **reconstruct exact occupancy state at any point in time** — even if the bus was offline for part of the trip — by replaying events in timestamp order.

**Connectivity state indicator in UI:**
```
  🟢 Online   — events sent live to dashboard
  🟡 Caching  — N events queued, will sync on reconnect
  🔴 No USB   — master disconnected
```

### Discount Calculation
```dart
int calculateFare(int baseFareCentavos, PassengerType type) {
  return (baseFareCentavos * type.multiplier).ceil();
}
int calculateDiscount(int baseFareCentavos, PassengerType type) {
  return baseFareCentavos - calculateFare(baseFareCentavos, type);
}
```

Philippine law reference:
- Senior Citizen: 20% discount (RA 9994, Expanded Senior Citizens Act)
- PWD: 20% discount (RA 10754)
- Student: 20% discount (subject to LGU/LTFRB resolution per route)

---

## IX. Screens (Flutter App)

### Modified: `DashboardScreen`
- NFC badge: "NFC ACTIVE" / "NFC IDLE" (replaces DOCKED)
- NFC state item (replaces UART)
- **Occupancy bar + passenger slot cards** (primary feature)
- FAB → "**+ Passenger**" (opens `BoardingSheet`)

### New: `OccupancyPanel` (embedded in Dashboard, main widget)

No grid/seat map — just a capacity bar and a list of active passenger slot cards:
```
  CDB-001 · Dalipuga Route
  ┌────────────────────────────────────────────┐
  │ Occupancy  ████████████░░░░░░░░  14 / 20  │
  │            Unpaid: 3   Paid: 11   70%      │
  └────────────────────────────────────────────┘

  ACTIVE PASSENGERS
  ┌──────────────────────────────────────────────────┐
  │ 🔴 #7   Country Hills   ₱10.40  Senior   ALARM  │ ← pulsing
  │ 🟠 #3   Brgy. Burol     ₱13.00  Regular  UNPAID │
  │ 🟠 #5   Tambo Terminal  ₱ 9.00  PWD      UNPAID │
  │ ✅ #1   Country Hills   ₱10.40  Senior   PAID   │
  │ ✅ #2   Brgy. Burol     ₱13.00  Student  PAID   │
  └──────────────────────────────────────────────────┘

  [ + New Passenger ]   Revenue today: ₱271.80
```

- Each row is a `PassengerSlot` card — tapping it expands to show:
  - Boarding stop, destination, boarded time
  - Fare breakdown (base + discount)
  - [Mark Paid] button (manual override if button press missed)
  - [Release Slave] button (when passenger alights, frees the slot)
- `#N` slot number = order boarded, resets each trip
- Capacity bar fills as more slaves are deployed
- ALARMING slots pulse red via `AnimationController`
- No seat grid, no `vehicles.json` layout config needed

### New: `BoardingSheet` (Modal bottom sheet, 2 tabs)

**Tab 1 — Quick (Sequential)**
```
Boarding Point
  ○ Auto-GPS: [DPWH Terminal ✓ — detected]
  ○ Manual:   [Select stop ▾]

Destination:  [Country Hills ▾]
Passenger:    [Regular ▾]     → Fare: ₱13.00
                               → Discount: —

[Tap Slave via NFC]
```
- Auto-GPS pre-fills boarding stop from nearest stop to current phone position
- Selecting "Manual" shows the stop picker (searchable list)

**Tab 2 — Queue (Multi-passenger)**
```
Pending Queue
  □ DPWH → Country Hills  ₱13.00  Regular    [Assign →]
  □ DPWH → Brgy. Burol   ₱10.00  Senior ★   [Assign →]

[+ Add Another Passenger]
```
- "Assign →" taps that queue item's slave via NFC
- Multiple passengers can be queued before any slave is assigned

### New: `ActiveSessionsPanel` (on dashboard)
```
ACTIVE PASSENGERS  (3 riding · 2 unpaid)
┌─────────────────────────────────────────────────────┐
│ 🔴 S2  Country Hills    ₱10.40 Senior    UNPAID ⚠  │  ← alarm near
│ 🟡 S1  Brgy. Burol     ₱10.00 Regular   UNPAID    │
│ ✅ S3  Tambo Terminal  ₱ 9.00 Student   PAID      │
└─────────────────────────────────────────────────────┘
```
Each row: tap to expand → shows boarding time, slave UID, change calc

### New: `ProximityAlarmBanner`
```
⚠ UNPAID FARE — 150m to Country Hills
   Slave S2 · ₱10.40 Senior Citizen
                                    [Mark Paid]  [Dismiss]
```
+ Phone vibration + local notification sound

### New: `ShiftSummaryScreen`
Accessible from the overflow menu. Shows end-of-shift stats:
```
SHIFT SUMMARY  —  May 26, 2026

Total Passengers:     23
Total Revenue:        ₱271.80
  Regular:   18 × avg ₱12.30  = ₱221.40
  Student:    3 × avg ₱10.50  = ₱ 31.50
  Senior:     2 × avg ₱ 9.45  = ₱ 18.90
  Discounts given:             = ₱ 15.60

[ Export JSON ]   [ Sync to Dashboard ]
```

---

## IX. Company Dashboard (Web)

A **separate web application** for the transport operator (DATRANSCO / DONSALS office).
Accessed via browser on an office PC or tablet.
Data arrives via: **Wi-Fi JSON upload** from the conductor's phone (when bus returns to terminal) OR **USB file export**.

### Dashboard Screens

#### 1. Fleet Overview (Home) — with Live Occupancy
```
TOMS Fleet Dashboard — DATRANSCO                  May 26, 2026

Active Buses: 3    Fleet Avg Occupancy: 61%    Total Revenue Today: ₱4,230.00

┌──────────┬──────────┬───────────┬──────────────────────┬──────────┬──────────┐
│ Bus ID   │ Route    │ Conductor │ Occupancy            │ Revenue  │ Status   │
├──────────┼──────────┼───────────┼──────────────────────┼──────────┼──────────┤
│ CDB-001  │ Dalipuga │ J. Reyes  │ ████████░░  14/20    │ ₱168.00  │ 🟢 Live  │
│ CDB-002  │ Dalipuga │ R. Santos │ █████░░░░░   9/20    │ ₱109.00  │ 🟡 Sync  │
│ CDB-003  │ Buru-un  │ M. Cruz   │ ████████████ 20/20   │ ₱240.00  │ 🟢 Live  │
└──────────┴──────────┴───────────┴──────────────────────┴──────────┴──────────┘
```
- **Occupancy** = slaves deployed / bus max capacity
- **🟢 Live** = events received in real-time (phone has internet)
- **🟡 N cached** = phone offline, N events queued — occupancy shown is last known state
- Click any row → opens per-bus **passenger slot list** (mirrors conductor's phone panel)
- `20/20` (FULL) highlighted — dispatcher knows to send the next bus
- Occupancy updates immediately as events arrive; no manual refresh needed

#### 2. Transaction Audit Log
- Searchable by: date range, bus, route, passenger type, slave UID
- Replaces the manual 6 PM ticket collection
- Each row: timestamp, bus, route, boarding stop, destination, fare, type, discount, UID
- **Export to CSV** for accounting

#### 3. Revenue Analytics
```
Revenue Trends — Last 7 Days

₱5,000 ┤                              ╭───╮
₱4,000 ┤              ╭──╮           │   │
₱3,000 ┤    ╭──╮     │  ╰──╮       ╭╯   ╰─
₱2,000 ┤───╯  ╰─────╯     ╰──────╯
       Mon   Tue   Wed   Thu   Fri   Sat   Sun

Passenger Type Breakdown:
  Regular  78%   ████████████████████░░░░░
  Student  12%   ████░░░░░░░░░░░░░░░░░░░░░
  Senior    7%   ██░░░░░░░░░░░░░░░░░░░░░░░
  PWD       3%   █░░░░░░░░░░░░░░░░░░░░░░░░
```

#### 4. Dispute Resolution
- Conductor or passenger disputes a fare → office searches by timestamp or UID
- Shows: exact boarding stop, destination, fare computed, passenger type, time
- Resolves the **2–3 weekly disputes** documented in the survey with objective data

#### 5. Reconciliation Report (replaces the 6 PM office visit)
```
End-of-Day Reconciliation — May 26, 2026

Bus CDB-001 (Dalipuga):
  Expected passengers:  23     ✓ Matches TOMS tap count
  Expected revenue:   ₱271.80
  Cash declared:      ₱271.80  ✓ Match
  Discounts applied:  ₱ 15.60  (3 Senior, 2 PWD)
  Status: ✅ BALANCED

Bus CDB-002 (Buru-un):
  Expected passengers:  18
  Expected revenue:   ₱214.50
  Cash declared:      ₱200.00  ⚠ DISCREPANCY: -₱14.50
  Status: ❌ REVIEW REQUIRED
```

### Dashboard Tech Stack Options

| Option | Description | Best For |
|--------|-------------|----------|
| **A — Static + JSON** | Flutter app exports a `.json` file; office opens a local HTML dashboard that reads it | Simplest, no server needed, works offline |
| **B — Local server** | Small Node.js or Python FastAPI server on office PC; app POSTs logs via Wi-Fi | Real-time multi-bus view, slightly more setup |
| **C — Cloud** | Firebase / Supabase; app syncs when any internet available | Best scalability, requires data plan |

**Recommendation for thesis/pilot:** Use **Option B** (local Node.js/FastAPI server on the company PC). The real-time sync model requires an HTTP endpoint — Option A (static JSON) cannot receive live pushes. Option B is still LAN-only (no data plan needed), self-hosted, and free. Option C (cloud) is the upgrade path once the pilot is validated.

---

## X. Flutter Packages Required

```yaml
# pubspec.yaml additions
geolocator: ^13.0.1              # Phone GPS
permission_handler: ^11.3.0      # Location + notification permissions
vibration: ^3.1.2                # Haptic alarm
flutter_local_notifications: ^17.2.2  # Audible alarm
share_plus: ^10.0.2              # Export shift JSON / CSV
http: ^1.2.0                     # Real-time event POST + sync queue flush
uuid: ^4.4.0                     # Session ID generation
connectivity_plus: ^6.0.3        # Internet state monitoring (online/offline)
```

---

## XI. Protocol Extension (Firmware, Phase 4 Only)

### New: `TOMS_MSG_ALARM_CMD` (Master → Slave)

Add to `shared_lib/protocol/protocol.h`:
```c
TOMS_MSG_ALARM_CMD = 0x30,

typedef struct __attribute__((packed)) {
    uint8_t  target_uid[16];
    uint8_t  alarm_type;      // 0 = proximity warning, 1 = final stop
    uint8_t  minutes_left;
} toms_alarm_cmd_t;
```

Master USB handler — new command from phone:
```json
{"cmd":"send_alarm", "uid":"A1B2C3D4E5F6", "alarm_type":0, "minutes":2}
```

Slave response: Flash screen red → "Please Pay — 2 min to stop"

---

## XIII. Build Phases

### Phase 1 — NFC + Occupancy Foundation (Flutter only)
- [ ] Update `DeviceStatus`: `uartState` → `nfcState`
- [ ] Add `PassengerType`, `TransitStop`, `PassengerSession`, `PendingPassenger` to models
- [ ] Add `SeatState`, `SeatRecord`, `OccupancySnapshot` to models
- [ ] Add `NfcTapEvent` model
- [ ] Database v2 migration (add columns)
- [ ] `OccupancyService` — seat state table, update from NFC tap + button_press + proximity
- [ ] `SessionService` (sessions + queue + fare calc + discounts)
- [ ] `UsbService` NFC event handlers (replace dock with nfc/nfc_tap)
- [ ] `AppState` wired to OccupancyService + SessionService + button_press → markPaid

### Phase 2 — Occupancy Panel + Boarding UI
- [ ] `assets/vehicles.json` — max capacity per bus ID (simple: `{"CDB-001": 20, ...}`)
- [ ] `assets/stops.json` (named stops + GPS coords)
- [ ] `StopService` (load + find nearest stop)
- [ ] **`OccupancyPanel`** — capacity bar + `PassengerSlot` card list, embedded in Dashboard
- [ ] Slot number auto-increment logic in `SessionService` (resets each trip)
- [ ] [Release Slave] action → frees slot, slave returned to pool
- [ ] `BoardingSheet` — Quick tab (GPS auto-boarding + stop picker + discount selector)
- [ ] `BoardingSheet` — Queue tab (multi-passenger queue)
- [ ] `ShiftSummaryScreen` with occupancy stats (peak occupancy, avg load, etc.)
- [ ] NFC tap → BOARD_COMMAND with `slot_number` (reused `seat_number` field) + fare + type

### Phase 3 — GPS + Proximity Alarm
- [ ] `GpsService` with `geolocator`
- [ ] `ProximityService` — 200m geofence, updates seat state to ALARMING
- [ ] Seat map tile pulses red when ALARMING
- [ ] `ProximityAlarmBanner` with [Mark Paid] action
- [ ] Vibration + local notification
- [ ] Android permissions: `ACCESS_FINE_LOCATION`, `VIBRATE`, `POST_NOTIFICATIONS`

### Phase 4 — Company Dashboard + Sync
- [ ] `SyncService` — export `OccupancySnapshot` + `ShiftReport` JSON
- [ ] Company dashboard (HTML/JS)
  - Fleet overview with live occupancy bars per bus
  - Per-bus seat map view (mirrors conductor's phone)
  - Transaction audit log (searchable)
  - Revenue analytics (per day/week, passenger type breakdown)
  - Reconciliation report (auto-flags discrepancy)
  - Dispute resolution search by UID / timestamp
- [ ] `share_plus` — conductor shares shift JSON at terminal

### Phase 5 — Slave Alarm (Firmware, Optional)
- [ ] `TOMS_MSG_ALARM_CMD` in `protocol.h`
- [ ] Slave: alarm cmd → red screen + "Please Pay" UI
- [ ] Master: `send_alarm` USB cmd → ESP-NOW to target slave
- [ ] Flutter `ProximityService` triggers slave alarm alongside phone alarm
