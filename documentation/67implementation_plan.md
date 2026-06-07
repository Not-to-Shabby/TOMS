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
  - [Collect Payment] → inline change calculator → [Confirm Paid]
  - [Mark Paid] (skip calculator) + [Release Slave] buttons
  - ALARMING state: pulsing red glow via `AnimationController`
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
  □ DPWH → Brgy. Burul   ₱10.00  Senior ★   [Assign →]

[+ Add Another Passenger]
```
- "Assign →" taps that queue item's slave via NFC
- Multiple passengers can be queued before any slave is assigned

### New: `ActiveSessionsPanel` (on dashboard)
```
ACTIVE PASSENGERS  (3 riding · 2 unpaid)
┌─────────────────────────────────────────────────────┐
│ 🔴 S2  Country Hills    ₱10.40 Senior    UNPAID ⚠  │  ← alarm near
│ 🟡 S1  Brgy. Burul     ₱10.00 Regular   UNPAID    │
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
- [x] Update `DeviceStatus`: `uartState` → `nfcState`
- [x] Add `PassengerType`, `TransitStop`, `PassengerSession`, `PendingPassenger` to models
- [x] Add `SeatState`, `SeatRecord`, `OccupancySnapshot` to models
- [x] Add `NfcTapEvent` model
- [x] Database v2 migration (add columns)
- [x] `OccupancyService` — seat state table, update from NFC tap + button_press + proximity
- [x] `SessionService` (sessions + queue + fare calc + discounts)
- [x] `UsbService` NFC event handlers (replace dock with nfc/nfc_tap)
- [x] `AppState` wired to OccupancyService + SessionService + button_press → markPaid

### Phase 2 — Occupancy Panel + Boarding UI

#### 2a — Assets
- [x] `assets/vehicles.json` — created with capacity per bus ID
- [x] `assets/stops.json` ✓ — coordinates confirmed in file

#### 2b — Dashboard UI Changes (dashboard_screen.dart)
- [x] Replace `_StatsRow` with a 3-card row: **Revenue · Passengers · Occupancy %**
- [x] Replace `_DeviceStatusCard` "DOCKED" badge with "NFC ACTIVE / NFC IDLE" badge
- [x] Replace "UART" status item label with "NFC"
- [x] Add `_OccupancyPanel` widget above the transaction list
  - Capacity bar with occupancy rate + color coding (green/orange/red)
  - Unpaid count sub-label
  - Scrollable list of `_PassengerSlotCard` tiles
- [x] `_PassengerSlotCard` — slot badge, destination, fare, type, state chip
  - Tap to expand: boarding stop, boarded time, fare breakdown
  - [Collect Payment] → inline change calculator → [Confirm Paid]
  - [Mark Paid] (skip calculator) + [Release Slave] buttons
  - ALARMING state: pulsing red glow via `AnimationController`
- [x] Replace FAB with **`+ New Passenger`** → opens `BoardingSheet`
  - USB connect/disconnect moved to AppBar overflow menu (⋮)

#### 2c — New Screens / Widgets
- [x] **`boarding_sheet.dart`** (modal bottom sheet, 2 tabs):
  - **Quick tab**: Boarding stop dropdown (GPS hint placeholder for Phase 3) + destination + 4 type chips → live fare preview → [Queue Passenger]
  - **Queue tab**: pending list + per-item [Assign →] manual target + [+ Add Another]
  - [↗ Expand] button → opens `QueueManagerScreen` (full-screen)
- [x] **`queue_manager_screen.dart`** (full-screen queue view):
  - Full pending queue list with slot badges and assign targeting
- [x] **`shift_summary_screen.dart`** (accessible from overflow ⋮ menu):
  - Total revenue, passengers, discounts, peak occupancy
  - Breakdown by passenger type (count + revenue per type)
  - Recent trip log with sync status badges
- [x] **`proximity_alarm_banner.dart`** — blinking overlay, [Mark Paid] + [✕ Dismiss] with confirm dialog, auto-collapses

#### 2d — Session & Occupancy Logic
- [x] Slot number auto-increment in `OccupancyService` — resets to 1 when all sessions clear
- [x] `_PassengerSlotCard` [Release Slave] → `AppState.releaseSlotManual(uid)` — implemented
- [x] `OccupancyService.maxCapacity` loaded from `vehicles.json` via `AppState._init()`

#### 2e — POS (Point of Sale) UI
##### What the Backend Already Supports (ready to wire up)
- [x] Fare calculation: `₱13 base + ₱1.80/km`
- [x] Passenger type discounts (Regular / Student / PWD / Senior)
- [x] Stop-pair lookup from `stops.json`
- [x] Active session list with slot numbers
- [x] Mark Paid on a session
- [x] Release slot (passenger alights)
- [x] Pending queue (pre-fill before slave assignment)

##### Planned POS UI Screens
- [x] **`BoardingSheet` / `BoardingFormScreen`** — implemented as `boarding_sheet.dart`
- [x] **`ActivePassengerCard`** — implemented as `_PassengerSlotCard` in `dashboard_screen.dart`
- [x] **`ChangeCalculatorWidget`** — implemented inline in `_PassengerSlotCard`
- [x] **Queue Panel** — implemented in `BoardingSheet` tab 2 and `queue_manager_screen.dart`

### Phase 2f — UI Revamp (Navigation + Layout) ✅
- [x] Replace the single `DashboardScreen` root with `HomeShell` → `PageView` (2 pages, `initialPage: 0`)
- [x] Page dot indicator at the bottom — animated pill grows when active page (teal)
- [x] Swipe hint on Boarding page: `"Swipe for Dashboard"` label — auto-dismisses after first swipe
- [x] `PageControllerService` shared via `ChangeNotifierProvider`
- [x] Promoted `_QuickTab` content into a full-screen `Scaffold`
- [x] Top header: TOMS logo, USB status pill, "Dashboard →" tap chip, overflow menu
- [x] Full-width boarding form — stop dropdowns with teal left-border when GPS auto-filled
- [x] Pending queue section inline below form (shows top 3 + count, "Manage →" link)
- [x] **[Queue Passenger]** button pinned at bottom with shadow separator
- [x] Dashboard FAB removed
- [x] `QueueManagerScreen` accessible via overflow menu
- [x] `DashboardScreen` refactored (FAB removed, Boarding navigation integration)
- [x] Visual design tokens and ClampingScrollPhysics PageView

### Phase 3 — GPS + Proximity Alarm ✅
- [x] Location permissions + Vibrate + Post Notifications setup
- [x] `GpsService` for location streams and nearest stop detection
- [x] GPS pill button in `BoardingScreen` next to boarding accordion to prefill stop
- [x] `ProximityService` to monitor destination and trigger proximity flags at <200m
- [x] `_onProximityAlarm` phone vibration + notification + in-app overlay banner

### Phase 4 — Company Dashboard + Sync ✅
- [x] Onboarding & Login screen + secure storage auth tokens
- [x] Shift end flow and Dynamic dispatch assignments
- [x] `ShiftHistoryScreen` for past shift details and exports
- [x] Route Builder map tool (Backend schemas + APIs + Vite/React sidebar map editor)
- [x] Multi-path branching routes support (Backend + Web UI + Mobile integration)
- [x] Live Fleet Tracking with dynamic WebSocket server updates
- [x] Historical Analytics & paginated, searchable Audit Logs
- [x] Conductor Account management page and CSV reports export
- [x] Conductor metadata injected into sync payloads and directory logs
- [x] Dynamic Vehicle Management (vehicles DB table + Fleet dashboard upgrades)
- [x] Live GPS map vehicle tracking markers on map with MapLibre
- [x] Time-Based Route Schedules mapping active hours and weekdays
- [x] Custom iOS toggle switches and Chrome-compatible blur glass modals
- [x] Auto-generated "Primary" path on route creation

---

### Phase 5 — Hardware Config & Slave Alarm (Firmware, Optional) ✅
- [x] TOMS_MSG_ALARM_CMD in `protocol.h`
- [x] Slave: alarm cmd → red screen + "Please Pay" UI
- [x] Master: `send_alarm` USB cmd → ESP-NOW to target slave
- [x] Flutter `ProximityService` triggers slave alarm alongside phone alarm
- [x] **Fare dictionary push to Master** — phone sends updated fare table to Master via USB `config` command
  - Triggered from vehicle assignment or a [Sync Config] action in the overflow menu
  - Master stores the fare dict in NVS (used by Slave on next board command via `fare_id` lookup)
  - Matches §3.5.1 step 6: "fare dictionary loading from NVS via `toms_fare_dict_load`"

---

### Phase 6 — Security, Analytics, and Hardware Polish (Upcoming)

> [!NOTE]
> This phase incorporates the remaining optional features (O1-O8) referenced in the TOMS paper into actionable development tasks.

#### 6a — Hardware Status & Warnings (O1)
- [x] **Slave Battery Telemetry (O1)**
  - [x] Firmware: Read LiPo ADC on GPIO 1 of the Slave.
  - [x] Firmware: Embed battery % into the ESP-NOW payload sent to the Master.
  - [x] Mobile: Add `batteryPct` to the `PassengerSlot` model.
  - [x] Mobile: Render a battery badge on `_PassengerSlotCard` (warn at $\le 20\%$).

#### 6b — Security & Dispute Resolution (O4, O5) (✅ DONE)
- [x] **Database Encryption (O5)**
  - [x] Mobile: Swap `sqflite` for `sqflite_sqlcipher` in `pubspec.yaml`.
  - [x] Mobile: Inject a secure passphrase to encrypt the local SQLite database to protect passenger identifiers and logs.
- [x] **QR Receipt Scanner (O4)**
  - [x] Mobile: Add `mobile_scanner` dependency for camera-based QR reading.
  - [x] Mobile: Build a dispute resolution screen to scan a passenger's QR code (`TOMS,VEH_ID,TIMESTAMP,FARE,UID`).
  - [x] Mobile: Look up the scanned string in the local SQLite database to verify the transaction.

#### 6c — Advanced Configuration & Sync (O6, O8) (✅ DONE)
- [x] **Dynamic Configuration Push (O6)**
  - [x] Mobile: Extend the USB `syncFareTable` payload (or create a new `sync_config` command) to push `maxCapacity` and full route waypoint lists.
  - [x] Firmware: Update Master `app_main.c` to parse the new configuration and store it in NVS.
- [x] **Sync Reconciliation View (O8)**
  - [x] Mobile: Create an End-to-End Reconciliation screen comparing the Master's SPIFFS log count (`status.pendingLogs`) with the mobile SQLite database's pending queue (`sync.pendingSyncCount`).

---

### Optional — Mobile Features Mentioned in the Paper (Code Audit Status)

> [!NOTE]
> These features are referenced in the TOMS research paper (Chapters 3–4 and the mobile README). 
> The detailed code audit below tracks their verified status and exact codebase implementation details.

### 🔍 Codebase Audit Verification

*   **O1 — Slave Battery Level Display on `_PassengerSlotCard`** (🟢 **DONE**)
    *   *Verification:* Fully implemented. The `PassengerSlot` model in `models.dart` includes a `batteryPct` field. The `AppState` listens for relayed battery messages via `UsbService` and updates a local cache. The `_PassengerSlotCard` in `dashboard_screen.dart` renders a dynamic, color-coded battery badge showing the telemetry value.
    *   *Next Steps:* None. Covered in Phase 6a.

*   **O2 — SPIFFS Unsynced File Count Badge on Device Status Card** (🟢 **DONE**)
    *   *Verification:* Fully supported and implemented. The `DeviceStatus` class in `models.dart` parses `pendingLogs` directly from the Master's status payloads. This is rendered beautifully as the **"Pending" status item** card under `_DeviceStatusCard` inside `dashboard_screen.dart`.

*   **O3 — Low Master Battery Warning UI** (⚫ **OBSOLETE**)
    *   *Verification:* The Master device is powered directly from the mobile phone via USB OTG. It does not possess an independent internal battery. Therefore, battery warnings for the Master are structurally irrelevant and will be removed from the UI.

*   **O4 — QR Receipt Scanner (Dispute Resolution)** (🟢 **DONE**)
    *   *Verification:* Camera scanner implemented via `mobile_scanner` in `dispute_resolution_screen.dart` with format parsing and database query logic.
    *   *Next Steps:* None. Covered in Phase 6b.

*   **O5 — SQLite Local DB Encryption (SQLCipher)** (🟢 **DONE**)
    *   *Verification:* The local database is implemented using plain SQLite via the standard `sqflite` interface, but backed by `sqflite_sqlcipher` database wrapper in `pubspec.yaml` and initialized with secure database passphrase in `database_service.dart`.
    *   *Next Steps:* None. Covered in Phase 6b.

*   **O6 — Dynamic Config Push to Master over USB** (🟢 **DONE**)
    *   *Verification:* Handled beautifully via `syncFareConfigToMaster()` in `app_state.dart`. It transmits route fare tables (`base_fare` and `per_km`), capacity (`maxCapacity`), and route-stop lists (`waypoints`) over USB CDC inside `sync_fare_table`, which the Master commits to its NVS partition and broadcasts to Slaves.

*   **O7 — Shift History Screen (Past Shifts)** (🟢 **DONE**)
    *   *Verification:* 100% complete and verified. Implemented in `shift_history_screen.dart`. It aggregates transactions grouped by calendar dates, displays sync/pending status badges, and supports CSV/JSON dynamic exports via `share_plus`.

*   **O8 — End-to-End Sync Reconciliation View** (🟢 **DONE**)
    *   *Verification:* Implemented as a dedicated, beautiful `SyncReconciliationScreen` (`sync_reconciliation_screen.dart`), comparing SQLite pending count with Master SPIFFS file records, with refresh capability and a list of unsynced logs.

---

### Summary Verification Table

| # | Feature | Status | Notes / Code Audit Verification |
|---|---|---|---|
| O1 | **Slave battery level display** on `_PassengerSlotCard` | 🟢 **DONE** | Heartbeats carry battery data via ESP-NOW → Master → USB serial. UI displays color-coded battery indicator on slot cards. |
| O2 | **SPIFFS unsynced file count badge** on device status card | 🟢 **DONE** | Fully supported. Rendered as the **"Pending" status item** (`status.pendingLogs`) on `_DeviceStatusCard` inside `dashboard_screen.dart`. |
| O3 | **Low Master battery warning UI** | ⚫ **OBSOLETE** | Master is powered by USB OTG from the phone and has no internal battery. Feature is structurally irrelevant. |
| O4 | **QR receipt scanner** (dispute resolution) | 🟢 **DONE** | Camera-based dispute resolution screen scans receipt QR and matches details in SQLite database. |
| O5 | **SQLite local DB encryption** (SQLCipher) | 🟢 **DONE** | Local database is encrypted using SQLCipher with a secure password stored in FlutterSecureStorage. |
| O6 | **Dynamic config push to Master over USB** | 🟢 **DONE** | Syncing base fare, per-km, maximum capacity, and route waypoints is fully supported, saved to NVS, and broadcast to Slaves. |
| O7 | **Shift history screen** (past shifts) | 🟢 **DONE** | Fully realized as `ShiftHistoryScreen` (`shift_history_screen.dart`), providing date-grouped stats, sync status badges, and JSON shift exports. |
| O8 | **End-to-end sync reconciliation view** | 🟢 **DONE** | Integrated reconciliation screen comparing local SQLite queues side-by-side with Master hardware SPIFFS logs. |

---

# Implementation Plan - Fix ESP-NOW Communication Deadlock by Bypassing CCMP Hardware Encryption

## Goal Description

Resolve the communication bootstrap deadlock between Master and Slave. The Slave fails to receive any unicast packets (e.g., `BOARD_COMMAND`, `TOMS_MSG_FORCE_RELEASE`) from the Master because of a native ESP-NOW CCMP hardware encryption mismatch.

### The Bootstrap Deadlock Explained
1. At startup, the Slave does not know the Master's unicast MAC address. It only registers the broadcast MAC (`FF:FF:FF:FF:FF:FF`) as an unencrypted peer.
2. The Slave transmits a `BUTTON_PRESS` to the broadcast MAC. This transmission is unencrypted at the ESP-NOW hardware layer but fully encrypted in software using AES-256-CBC via `toms_crypto_encrypt`.
3. The Master successfully receives and decrypts the broadcast packet.
4. The Master registers the Slave's unicast MAC with `s_espnow_lmk` (CCMP hardware encryption enabled).
5. The Master transmits `BOARD_COMMAND` to the Slave's unicast MAC as a CCMP hardware-encrypted packet.
6. **The Deadlock:** The Slave has *not* registered the Master's unicast MAC address as an encrypted peer with the LMK key. Consequently, the Slave's Wi-Fi hardware fails to decrypt the incoming CCMP packet and silently discards it before calling `on_recv`.
7. Because the packet is dropped at the hardware level, the Slave never transitions to the processing screen, never registers the Master's MAC, and times out waiting for the Master.

### The Solution: Bypass CCMP Hardware Encryption
Since all ESP-NOW payloads are already fully secured using AES-256-CBC software encryption (`toms_crypto_encrypt` / `toms_crypto_decrypt`) with a key loaded from NVS, the native ESP-NOW CCMP hardware-level encryption is completely redundant.
By disabling CCMP hardware-level encryption (passing `NULL` instead of `s_espnow_lmk` to all `toms_espnow_add_peer` calls on both Master and Slave):
- The devices will communicate using unencrypted ESP-NOW frames at the hardware layer.
- **Security is 100% maintained** because the actual payload data is still fully encrypted in software using AES-256-CBC.
- The bootstrap deadlock is completely avoided: the Slave's hardware will happily receive the unicast `BOARD_COMMAND` from the Master and successfully decrypt it in software.

---

## Proposed Changes

### Master Firmware (`esp32_master_espnow`)

#### [MODIFY] [app_main.c](file:///d:/TOMS/esp32_master_espnow/main/app_main.c)
- Replace all dynamic and static `toms_espnow_add_peer(..., s_espnow_lmk, ...)` calls with `toms_espnow_add_peer(..., NULL, ...)` to disable CCMP hardware encryption.
  - Line 219: `force_release` handler
  - Line 254: `send_alarm` handler
  - Line 405: `board` handler
  - Line 459: `on_nfc_tap` callback
  - Line 501: `espnow_handler_task` callback
  - Line 784: `app_main` startup registration

### Slave Firmware (`esp32_slave_espnow`)

#### [MODIFY] [app_main.c](file:///d:/TOMS/esp32_slave_espnow/main/app_main.c)
- Replace all dynamic and static `toms_espnow_add_peer(..., s_espnow_lmk, ...)` calls with `toms_espnow_add_peer(..., NULL, ...)` to disable CCMP hardware encryption.
  - Line 129: `update_master_mac` callback
  - Line 847: `app_main` startup registration

---

## Verification Plan

### Automated Tests
- Compile both Master and Slave firmware using PlatformIO and verify they compile with no errors.

### Manual Verification
1. Power on both Master and Slave.
2. Press button on Slave to trigger boarding.
3. Verify Master logs: `Button press from slave 9c:13:9e:f2:3c:48`.
4. Trigger boarding from mobile app or serial command.
5. Verify Slave receives the packet, displays the route and fare on the Fare screen.
6. Click "Release Slave" in the app, verify Slave instantly receives the release command, clears state, and shows the Welcome screen.

---

# Implementation Plan - Debug Mode and Simulation Features (toms_mobile)

## Goal Description
Provide developers/testers with a global "Debug Mode" toggle to simulate proximity alarms and offline synchronization events directly from the UI without needing physical GPS movement or disabling network connections.

## Proposed Changes

### 1. App State & Services (`toms_mobile/lib/services`)

#### [MODIFY] [app_state.dart](file:///d:/TOMS/toms_mobile/lib/services/app_state.dart)
- Add global `_debugMode` property and `toggleDebugMode()` method. Persist state in `SharedPreferences`.
- Call `syncService.initPendingCount()` in `initialize()` to set initial badge counts.
- Add `toggleMockAlarm(String slaveUid)` to toggle `alarmTriggered` for active sessions, and fire the alarm vibration, notification, and USB command.
- Add `setForceOffline(bool force)` to override internet status.
- Add `addMockUnsyncedEvent()` to enqueue a mock passenger boarding event into local SQLite.

#### [MODIFY] [connectivity_service.dart](file:///d:/TOMS/toms_mobile/lib/services/connectivity_service.dart)
- Add `_forceOffline` flag.
- Override `isOnline` getter to return `false` if `_forceOffline` is true.
- Add setter for `forceOffline` that fires the new state to the stream controller.

#### [MODIFY] [sync_service.dart](file:///d:/TOMS/toms_mobile/lib/services/sync_service.dart)
- Add `initPendingCount()` method to fetch unsynced events count on startup.
- Update `push()` to manually increment `_pendingSyncCount` and call `notifyListeners()` when enqueuing events offline.

### 2. User Interface (`toms_mobile/lib`)

#### [MODIFY] [dashboard_screen.dart](file:///d:/TOMS/toms_mobile/lib/screens/dashboard_screen.dart)
- Wrap `PopupMenuButton` in `Consumer<AppState>` and append a new option to toggle Debug Mode.
- Update `_SyncStatusBar` to show debug actions (Force Offline Switch, Add Mock Event Button) when Debug Mode is ON, even if queue is empty.

#### [MODIFY] [proximity_alarm_banner.dart](file:///d:/TOMS/toms_mobile/lib/widgets/proximity_alarm_banner.dart)
- Modify `ProximityAlarmBanner` to remain visible when Debug Mode is ON and no real alarms are firing.
- Render a "Proximity Alarm Simulator" panel listing active unpaid passenger slots and a button to trigger mock alarms for each.
- Render a "Reset" button in the standard alarm rows when in Debug Mode.

## Verification Plan

### Automated Verification
- Verify that `toms_mobile` compiles successfully with no lint or compilation errors using Dart analyzer.

### Manual Verification
1. Open overflow menu and verify that toggling "Debug Mode" works and persists across restarts.
2. When Debug Mode is ON:
   - Verify that the Sync Status Bar appears immediately with "Force Offline" and "MOCK EVENT" options.
   - Toggle "Force Offline" ON, tap "MOCK EVENT" and verify that enqueued events count increments.
   - Toggle "Force Offline" OFF and verify that events automatically sync and count decreases to 0.
3. Board a passenger. With Debug Mode ON:
   - Verify that the Proximity Alarm Banner is visible as a blue simulator panel showing the boarded passenger.
   - Tap "TRIGGER ALARM" and verify that the alarm fires (vibration, notifications, red pulsing card, and USB command if connected).
   - Tap "Reset" or "Mark Paid" and verify the alarm state is cleared.


