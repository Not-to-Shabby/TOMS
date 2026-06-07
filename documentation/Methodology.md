# Methodology

**NFC-Based Point-of-Sale and Passenger Monitoring System for Local Public Minibuses**

---

# 3.5 System Architecture

This section describes the overall system architecture of the TOMS prototype. The architecture defines how the three physical subsystems — the Master terminal, the Slave terminal, and the conductor's smartphone — communicate with each other and with the cloud backend to process boarding events, track occupancy, and synchronize records.

## 3.5.1 Three-Tier Hardware Architecture

The TOMS system is organized into three hardware tiers, each fulfilling a distinct operational role:

**Table 3.3. TOMS Hardware Tiers**

| Tier | Device | Primary Role |
| :---- | :---- | :---- |
| Tier 1 — Master Terminal | ESP32-S3 + PN532 NFC | Coordinates all boarding transactions. Initiates NFC-DEP handshakes with Slave terminals, relays events to the phone over USB, receives commands from the phone, and broadcasts alarm signals to Slaves via ESP-NOW radio. Logs all transactions to local SPIFFS flash as a backup. |
| Tier 2 — Slave Terminal | ESP32-S3 + PN532 NFC + ST7735S LCD + Button | Passenger-facing device. Receives boarding commands via NFC tap, displays fare and QR code receipt on the LCD screen, and notifies the Master of button presses via ESP-NOW. Operates on battery with deep-sleep cycling. |
| Tier 3 — Conductor's Phone | Android smartphone (Flutter app) | The conductor's operational interface. Manages the boarding queue, computes fares using GPS, maintains the local encrypted SQLite database, monitors passenger proximity to destinations, and synchronizes records to the cloud when connectivity is available. |

## 3.5.2 Communication Links

The three tiers are connected by three distinct communication channels, each selected for a specific operational requirement:

**Table 3.4. Communication Channels**

| Link | Technology | Direction | Range | Purpose |
| :---- | :---- | :---- | :---- | :---- |
| Link A — NFC-DEP | PN532 peer-to-peer (ISO/IEC 18092) at 13.56 MHz | Master ↔ Slave | ≤ 4 cm (contact tap) | Boarding command delivery. The Master transmits a compact 6-byte fare command to the Slave during a physical tap. The Slave responds with a 1-byte acknowledgment. The short range acts as a physical security barrier, ensuring only intentional taps register. |
| Link B — ESP-NOW | Espressif proprietary, 2.4 GHz (CCMP-encrypted) | Master ↔ Slave (bidirectional) | Up to 100 m (in-vehicle) | Asynchronous event relay. The Slave sends button press notifications and periodic heartbeat packets to the Master. The Master sends proximity alarm commands to specific Slaves. Delivery occurs in under 10 milliseconds without Wi-Fi association. |
| Link C — USB CDC-ACM | TinyUSB virtual serial over USB-C OTG | Master ↔ Phone | Wired (cable) | Real-time data stream. The Master sends JSON-formatted boarding events and device status reports to the phone. The phone sends fare configuration updates and alarm trigger commands to the Master. Communication runs at 115,200 baud with newline-delimited JSON framing. |

A fourth, non-device link connects the phone to the cloud:

| Link | Technology | Direction | Purpose |
| :---- | :---- | :---- | :---- |
| Link D — REST API | HTTPS over cellular data or Wi-Fi | Phone → Cloud | The sync service uploads boarding events as JSON POST requests to the Supabase backend. The company web dashboard queries the same database. This link is intermittent by design — the offline-first architecture guarantees operation when it is unavailable. |

## 3.5.3 Security Architecture

The system applies encryption and authentication at multiple layers to protect boarding records and prevent device spoofing:

- **Hardware identity:** Each ESP32-S3 carries a factory-burned eFuse MAC address that cannot be altered. This address is embedded in NFC-DEP handshakes and included in every packet, providing tamper-proof device authentication.
- **Link-layer encryption:** All ESP-NOW radio frames are encrypted using CCMP (AES-128) with a pre-shared Local Master Key. Any replayed or tampered frame fails the integrity check and is silently discarded.
- **Application-layer encryption:** Sensitive payload fields are encrypted using AES-256 via the mbedTLS library before transmission. Encryption keys are stored in the ESP32-S3's Non-Volatile Storage (NVS) partition.
- **Local database encryption:** The phone's SQLite database is encrypted at rest using SQLCipher (AES-256). The passphrase is stored in Android's hardware-backed keystore.
- **API authentication:** All cloud requests include a bearer token issued during conductor login, ensuring that uploaded records are traceable to an authenticated operator.

## 3.5.4 Protocol Message Types

All packets exchanged between the Master and Slave terminals follow a unified framing format: a 2-byte sync word (`0xAA55`), a 1-byte payload length, a 1-byte message type, a 1-byte sequence number, the variable-length payload, and a 2-byte CRC-16-CCITT checksum. The following message types are defined in the shared protocol library:

**Table 3.5. TOMS Protocol Message Types**

| Code | Message Type | Direction | Description |
| :---- | :---- | :---- | :---- |
| 0x01 | PASSENGER_BOARD | Slave → Master | Confirmed passenger boarding event |
| 0x02 | BUTTON_PRESS | Slave → Master | Physical button press notification (payment signal) |
| 0x04 | RELEASE | Slave → Master | Passenger alighted, slot release request |
| 0x11 | CONFIG_SYNC | Master → Slave | Route, vehicle ID, base fare, and epoch time synchronization |
| 0x15 | NFC_BOARD_CMD | Master → Slave (NFC) | Compact 6-byte boarding command (fare index + slot + timestamp) |
| 0x22 | NFC_ACK | Slave → Master (NFC) | 1-byte NFC acknowledgment (OK, UID mismatch, or dictionary miss) |
| 0x30 | ALARM_CMD | Master → Slave (ESP-NOW) | Proximity alarm command with target UID and alarm type |
| 0xF0 | HEARTBEAT | Slave → Master (ESP-NOW) | Keep-alive with battery voltage and state-of-charge |
| 0xF1 | TIME_SYNC | Master → Slave | Epoch timestamp synchronization |

## 3.5.5 End-to-End Data Flow

The complete data pipeline from a single boarding event to the company dashboard follows this sequence:

```
[Conductor]                [Master]               [Slave]                [Cloud]
    │                         │                       │                     │
    ├─ Selects destination ──►│                       │                     │
    │  & passenger type       │                       │                     │
    │  (Phone UI)             │                       │                     │
    │                         │                       │                     │
    ├─ Taps Master to ───────►├── NFC-DEP tap ───────►│                     │
    │  Slave (physical)       │   (6-byte cmd)        ├─ Displays fare      │
    │                         │                       │  & QR on LCD        │
    │                         │◄── NFC ACK ───────────┤                     │
    │                         │                       │                     │
    │◄─ USB JSON event ───────┤                       │                     │
    │   ("nfc_tap" + UID)     │                       │                     │
    │                         │                       │                     │
    ├─ Commits to SQLite      │◄── ESP-NOW ───────────┤                     │
    │  (synced = 0)           │   (PASSENGER_BOARD)   │                     │
    │                         │                       │                     │
    │◄─ USB JSON event ───────┤── Logs to SPIFFS      │                     │
    │   ("passenger" + fare)  │                       │                     │
    │                         │                       │                     │
    ├─ If online: POST ──────────────────────────────────────────────►│      │
    │  to REST API            │                       │               │     │
    │  (bearer token)         │                       │    Cloud DB stores  │
    │                         │                       │    event            │
    │  Mark synced = 1        │                       │               │     │
    │                         │                       │               │     │
    │  If offline: retain     │                       │    Dashboard  │     │
    │  locally, retry at      │                       │    queries &  │     │
    │  30 s intervals         │                       │    renders    │     │
```

Suggested Figure: Figure 3.3. System Architecture of the NFC-Based POS and Passenger Monitoring System

Recommended architecture flow: NFC Tap → Master Terminal → USB CDC → Conductor Phone → SQLite → Internet Check → Supabase Cloud → Company Dashboard

---

# 3.7 Prototype Development

This section describes the construction and integration of the TOMS prototype. The system is composed of three physical units — the Master handheld terminal, the Slave passenger terminal, and the conductor's Android smartphone — each developed as a separate subsystem and verified independently before full integration.

## 3.7.1 Assembly of the Master Terminal

The Master terminal was assembled around the ESP32-S3 Super Mini development board operating at 240 MHz with 4 MB of QIO flash. A PN532 NFC transceiver module was wired to the ESP32-S3's I2C bus for peer-to-peer NFC-DEP boarding interactions. The device connects to the conductor's phone via its native USB-C port operating in CDC-ACM virtual serial mode. Power is supplied by a rechargeable lithium-polymer battery monitored through the ESP32-S3's onboard ADC channel.

The firmware was developed in C using the ESP-IDF framework and compiled through PlatformIO. A shared communications library containing the packet protocol, AES-256 encryption module, and ESP-NOW driver was linked into the Master firmware from a common source directory to ensure byte-level protocol compatibility with the Slave terminal.

## 3.7.2 Assembly of the Slave Terminal

The Slave terminal was assembled using an identical ESP32-S3 Super Mini board. A PN532 NFC transceiver was connected over I2C in Target mode. A 1.8-inch ST7735S TFT LCD (128×160 pixels) was connected over SPI to display fare information, destination labels, and QR code receipts to passengers. A single physical push-button was wired to a GPIO pin to serve as the payment signal input. The LVGL graphics library (v8.3) was integrated to manage screen rendering, widget layout, and font display.

The Slave firmware operates as a passive, event-driven terminal. Upon receiving a boarding command from the Master — either through NFC tap or ESP-NOW radio — it renders the assigned fare and QR receipt on the display. During idle periods, the Slave enters deep-sleep mode and periodically wakes to transmit a heartbeat packet to the Master before returning to sleep, thereby conserving battery for an entire operating shift.

## 3.7.3 Development of the Mobile Application

The TOMS mobile application was developed in Dart using the Flutter framework, targeting Android devices. The application communicates with the Master terminal over a USB OTG serial link using the `usb_serial` plugin. GPS tracking is implemented through the `geolocator` plugin to determine the vehicle's current transit stop and to monitor proximity to passenger destinations. State management across the user interface is handled by the Provider library. The local transaction database uses SQLCipher-encrypted SQLite for offline-first data caching, with encryption keys stored in Android's hardware-backed keystore via `flutter_secure_storage`.

## 3.7.4 Initial Functional Testing

Each subsystem was verified independently before integration. The Master firmware was tested for NFC-DEP Initiator polling, ESP-NOW radio transmission and reception, USB CDC serial output, and SPIFFS log persistence. The Slave firmware was tested for NFC Target detection, LCD screen rendering, QR code generation, push-button input debouncing, and deep-sleep wake cycling. The mobile application was tested for USB serial connectivity, GPS permission acquisition, database read/write operations, and Provider-based UI state propagation.

Integration testing was then performed by connecting all three units. A tap-to-board cycle was validated end-to-end: the phone queues a passenger, the conductor taps the Master to the Slave via NFC, the Slave displays the fare and QR receipt, and the Master streams the boarding event to the phone over USB where it is committed to the local database.

Suggested Figures:
- Figure 3.5. Assembled Master Terminal (Front and Rear View)
- Figure 3.6. Assembled Slave Terminal Showing LCD Display
- Figure 3.7. TOMS Mobile Application — Boarding Screen Interface

---

# 3.8 NFC Seat Card Registration and Passenger Monitoring Process

This section describes how reusable Slave terminals function as seat markers and digital fare records within the TOMS system. Unlike conventional RFID ticketing systems that require passengers to carry personal smart cards, TOMS uses conductor-managed NFC terminal devices that are assigned to passengers at boarding and retrieved upon alighting.

## 3.8.1 Terminal Registration via eFuse MAC

Each Slave terminal carries a permanent, factory-burned 48-bit hardware identifier stored in the ESP32-S3's electronic fuses (eFuses). This identifier — the eFuse MAC address — cannot be altered, erased, or duplicated by any software operation. The Slave's MAC address is embedded in the 10-byte NFCID3 field transmitted during every NFC-DEP handshake, so the Master implicitly authenticates the identity of the Slave being tapped without a separate registration step.

No manual registration or pairing ceremony is required. The Slave's identity is inherent in its hardware and is recognized automatically during every NFC tap interaction.

## 3.8.2 Terminal Assignment to a Passenger

When a passenger boards the minibus, the conductor performs the following sequence through the TOMS mobile application:

1. The conductor selects the passenger's destination stop and passenger type (Regular, Student, PWD, or Senior) in the boarding sheet interface.
2. The application computes the base fare using the Haversine distance between the boarding stop and the destination stop, and applies the applicable discount rate.
3. The passenger entry is added to a pending queue within the application.
4. The conductor taps the Master terminal against an available Slave terminal via NFC.
5. The Master detects the Slave's eFuse MAC through the NFC-DEP handshake, sends a compact boarding command containing the fare index and slot number, and immediately notifies the phone.
6. The phone's session service binds the pending queue entry to the detected Slave UID, creating an active passenger session.
7. The Slave terminal displays the passenger's destination, fare amount, and a QR code receipt on its LCD screen.
8. The Slave terminal is physically handed to the passenger.

## 3.8.3 Passenger Count and Occupancy Update

Each time a Slave terminal is assigned to a passenger, the occupancy service assigns a sequential slot number (#1, #2, #3, and so on) in the order of boarding. This number serves as the passenger's short identifier on both the Slave terminal screen and the conductor's dashboard. The occupancy rate is computed as the ratio of deployed Slave terminals to the configured maximum vehicle capacity and is displayed as a percentage on the dashboard.

## 3.8.4 Payment and Fare Collection

When the passenger is ready to pay, they press the physical button on the Slave terminal. The Slave transmits a `BUTTON_PRESS` packet to the Master via ESP-NOW. The Master forwards this event to the phone, where the conductor confirms the payment. The dashboard updates the passenger's slot state from UNPAID to PAID.

## 3.8.5 Terminal Release and Reuse

When the passenger alights, the conductor retrieves the Slave terminal and releases the slot through the mobile application. The occupancy count decreases, and the Slave terminal becomes available for assignment to the next boarding passenger. The slot numbering sequence does not reset until all passengers have alighted and the vehicle is empty, at which point it restarts from #1.

Suggested Figure: Figure 3.8. NFC Seat Card Transaction Flow

Recommended flow: NFC Tap → eFuse MAC Authentication → Session Assignment → Occupancy Update → Fare Display & QR Receipt → Button Press → Payment Confirmation → Slot Release → Terminal Available

**Table 3.7. Passenger Session Data Fields**

| Field | Description |
| :---- | :---- |
| Slave UID | Unique eFuse MAC address of the assigned Slave terminal |
| Slot Number | Sequential boarding position number (resets per trip) |
| Boarding Stop | GPS-derived transit stop where the passenger boarded |
| Destination Stop | Conductor-selected destination from the route stop list |
| Passenger Type | Regular, Student, PWD, or Senior |
| Base Fare | Computed fare before discount (in centavos) |
| Final Fare | Fare after discount application (in centavos) |
| Discount Amount | Difference between base fare and final fare |
| Fare Status | Unpaid, Paid, or Alarming |
| Boarding Timestamp | Date and time of NFC tap |

---

# 3.9 GPS-Based Fare Calculation Process

This section describes how the TOMS system calculates passenger fares using GPS coordinates and a distance-based fare matrix derived from the Land Transportation Franchising and Regulatory Board (LTFRB) rate structure.

## 3.9.1 GPS Coordinate Capture

The TOMS mobile application uses the phone's built-in GPS receiver to track the vehicle's position in real time. The GPS service is configured with high accuracy and a 20-meter distance filter, meaning position updates are received each time the vehicle moves at least 20 meters. Upon each update, the application computes the nearest transit stop by comparing the phone's coordinates against a pre-loaded route stop list using the Haversine formula.

The route stop list is stored as a JSON asset bundled with the application. Each stop entry contains a unique identifier, a human-readable name, and the geographic latitude and longitude coordinates. The current route used for development and testing contains nine stops along the Buru-un to City Proper corridor in Iligan City.

**Table 3.8a. Route Stop Coordinates**

| Stop ID | Stop Name | Latitude | Longitude |
| :---- | :---- | :---- | :---- |
| 1 | Buru-un (Start) | 8.1947 | 124.1706 |
| 2 | Fuentes | 8.2033 | 124.1798 |
| 3 | Nunucan | 8.2078 | 124.1855 |
| 4 | Tominobo | 8.2144 | 124.2033 |
| 5 | Camague | 8.2211 | 124.2215 |
| 6 | Suarez | 8.2230 | 124.2300 |
| 7 | Tubod | 8.2255 | 124.2365 |
| 8 | Mahayahay | 8.2272 | 124.2400 |
| 9 | City Proper / Post Office | 8.2285 | 124.2430 |

## 3.9.2 Distance Computation

When the conductor selects a boarding stop and a destination stop, the application calculates the great-circle distance between the two coordinates using the Haversine formula:

$$a = \sin^{2}\left(\frac{\Delta\phi}{2}\right) + \cos(\phi_1)\cos(\phi_2)\sin^{2}\left(\frac{\Delta\lambda}{2}\right)$$

$$d = 2R \cdot \arctan2\left(\sqrt{a}, \sqrt{1-a}\right)$$

where *R* = 6,371,000 meters (mean Earth radius), *φ* represents latitude in radians, and *λ* represents longitude in radians. The result *d* is the distance in meters between the boarding and destination stops.

## 3.9.3 Fare Matrix and Computation

The fare computation follows a base-plus-increment model consistent with the LTFRB rate structure for local public utility minibuses:

- **Base fare:** ₱15.00 for the first 4 kilometers.
- **Per-kilometer increment:** ₱2.50 for each additional kilometer beyond 4 km (rounded up to the next whole kilometer).

The fare for a given trip is computed as:

$$\text{Fare} = \begin{cases} ₱15.00 & \text{if } d \leq 4\text{ km} \\ ₱15.00 + \lceil(d - 4)\rceil \times ₱2.50 & \text{if } d > 4\text{ km} \end{cases}$$

**Table 3.8b. Sample Fare Matrix**

| Distance Range | Base Fare | Extra km | Per-km Rate | Total Fare |
| :---- | :---- | :---- | :---- | :---- |
| 0–4 km | ₱15.00 | 0 | — | ₱15.00 |
| 4.1–5 km | ₱15.00 | 1 | ₱2.50 | ₱17.50 |
| 5.1–6 km | ₱15.00 | 2 | ₱2.50 | ₱20.00 |
| 6.1–7 km | ₱15.00 | 3 | ₱2.50 | ₱22.50 |
| 7.1–8 km | ₱15.00 | 4 | ₱2.50 | ₱25.00 |
| 8.1 km and above | ₱15.00 | ⌈d−4⌉ | ₱2.50 | ₱15.00 + ⌈d−4⌉ × ₱2.50 |

## 3.9.4 Discount Application

After computing the base fare, the system applies discount rates based on the passenger type classification:

**Table 3.8c. Passenger Discount Categories**

| Passenger Type | Discount Rate | Fare Multiplier |
| :---- | :---- | :---- |
| Regular | 0% | 1.00× |
| Student | 20% | 0.80× |
| Person with Disability (PWD) | 20% | 0.80× |
| Senior Citizen | 20% | 0.80× |

The discounted fare is computed by applying the fare multiplier to the base fare. In accordance with LTFRB rules, the final fare is rounded to the nearest whole Philippine Peso.

## 3.9.5 Fare Validation

The computed fare is displayed on the mobile application's boarding sheet for the conductor to review before confirming the transaction. Once confirmed, the fare is transmitted to the Master terminal via USB, which forwards it to the Slave terminal via NFC for on-screen display to the passenger. The fare amount, discount, boarding stop, and destination stop are recorded together in the local database for audit purposes.

Suggested Figure: Figure 3.9. GPS-Based Fare Calculation Flowchart

Recommended flow: GPS Position → Nearest Stop Identification → Conductor Selects Destination → Haversine Distance Computation → Fare Matrix Lookup → Discount Application → Final Fare Display → Transaction Record Creation

---

# 3.10 Offline-First Data Storage and Synchronization

This section describes how the TOMS system stores transaction records locally and synchronizes them to the central cloud server. The system is designed to continue processing boarding events without interruption even when cellular connectivity is weak or completely unavailable along portions of the route.

## 3.10.1 Internet Availability Check

The mobile application monitors internet connectivity using the `connectivity_plus` package, which listens to the Android system's network state broadcasts. The connectivity service maintains a boolean flag indicating whether the device currently has access to a mobile data or Wi-Fi network. This flag is evaluated at two points: immediately when a new boarding event is created, and periodically by a background retry timer that runs at 30-second intervals.

## 3.10.2 Offline Transaction Storage

Every boarding event is committed to the local SQLCipher-encrypted SQLite database immediately upon creation, regardless of the current network state. The database schema stores each transaction with the following fields:

**Table 3.9. Transaction Record Structure (SQLite `passenger_logs` Table)**

| Data Field | Type | Description |
| :---- | :---- | :---- |
| id | INTEGER | Auto-incremented unique record number |
| timestamp | INTEGER | Unix epoch time of the boarding event |
| boarding_type | INTEGER | Trigger source (NFC tap or button press) |
| fare_centavos | INTEGER | Final computed fare in centavos |
| seat_number | INTEGER | Assigned slot number |
| route_id | INTEGER | Active route identifier |
| passenger_id | TEXT | eFuse MAC address of the Slave terminal |
| synced | INTEGER | Synchronization status (0 = pending, 1 = synced) |
| passenger_type | INTEGER | Passenger category (Regular, Student, PWD, Senior) |
| discount_centavos | INTEGER | Discount amount applied |
| boarding_stop | TEXT | Name of the boarding transit stop |
| destination_stop | TEXT | Name of the destination transit stop |
| destination_lat | REAL | Latitude of the destination stop |
| destination_lon | REAL | Longitude of the destination stop |

A separate `occupancy_events` table stores sync-queue events in JSON format for cloud upload, with its own independent `synced` flag.

## 3.10.3 Pending Record Marking

Every record is inserted with `synced = 0` (pending) by default. This flag is only updated to `synced = 1` after the cloud server returns a successful HTTP response (status code 200–299) for that specific record. This design ensures that no record is ever marked as synced unless the server has explicitly confirmed receipt.

## 3.10.4 Synchronization When Connectivity Returns

When the connectivity service detects that internet access has been restored, the sync service executes the following queue flush sequence:

1. Query the local database for all records where `synced = 0`, ordered by timestamp (oldest first).
2. For each pending record, transmit the event payload as a JSON HTTP POST request to the cloud API endpoint with an authorization bearer token.
3. If the server responds with a success status (200–299), mark the record's `synced` flag as `1` in the local database.
4. If the server responds with a failure status or the request times out (5-second timeout), stop the flush sequence immediately to preserve chronological order.
5. The background retry timer re-attempts the flush every 30 seconds for as long as the device remains online and pending records exist.

## 3.10.5 Duplicate Synchronization Prevention

Duplicate uploads are prevented by the `synced` flag column. Only records with `synced = 0` are included in the upload query. Once a record's flag is set to `1`, it is permanently excluded from future sync cycles. Because the flush sequence processes records in strict chronological order and halts on the first failure, no record can be skipped and then re-sent out of sequence.

## 3.10.6 Database Security

The local SQLite database is encrypted at rest using SQLCipher, a full-database encryption extension for SQLite using AES-256. The encryption passphrase is auto-generated as a random UUID pair at first launch and stored securely in Android's hardware-backed keystore via the `flutter_secure_storage` plugin. This prevents transaction records from being extracted or read by unauthorized parties, even if physical access to the device is obtained.

Suggested Figure: Figure 3.10. Offline-First Data Synchronization Process

Recommended flow: Transaction Created → Save to Encrypted SQLite (synced = 0) → Check Connectivity → If Online: POST to Cloud API → If Success: Mark synced = 1 → If Offline: Retain Locally → Retry Timer (30 s) → Re-check Connectivity → Flush Pending Queue

---

# 3.11 Web-Based Dashboard Development

This section describes the dashboard interfaces available in the TOMS mobile application and the cloud-backed company dashboard that receives and displays synchronized occupancy and revenue data. The system provides two levels of dashboard visibility: a real-time conductor dashboard on the mobile phone, and a fleet-wide company dashboard accessible through a web browser.

## 3.11.1 Conductor Dashboard (Mobile Application)

The conductor dashboard is the primary operating interface presented on the Android phone during an active shift. It is organized into the following functional sections:

**Table 3.10a. Conductor Dashboard Sections**

| Section | Description |
| :---- | :---- |
| Device Status Card | Displays the Master terminal's battery level, number of pending SPIFFS log files, and NFC interface state. Visible only when the Master is connected via USB. |
| Daily Statistics Row | Shows three summary cards: today's cumulative revenue (in Philippine Pesos), total passenger count for the current day, and current occupancy rate as a percentage of maximum capacity. |
| Proximity Alarm Banner | Appears dynamically when any active unpaid passenger's destination is within the configured alarm radius (default: 100 meters). Displays the passenger's slot number, destination name, and elapsed boarding time. |
| Active Passengers Panel | Lists all passengers currently holding Slave terminals. Each passenger slot card shows the slot number, destination, fare, passenger type, payment state (UNPAID, PAID, or ALARM), and Slave battery level. Tapping a card expands it to reveal the full fare breakdown, a change calculator, and action buttons to collect payment or release the slot. |
| Recent Transactions List | Displays the most recent boarding events with timestamp, destination, fare, passenger type, and cloud sync status (synced or pending). |
| Sync Status Bar | Appears at the top of the dashboard when the sync queue contains unsent events, displaying the number of events queued for upload. |

Additional screens accessible from the dashboard menu:

- **Shift Summary:** Displays the current shift's total revenue, passenger count, and fare breakdown by passenger type.
- **Shift History:** Lists historical completed shifts with timestamped summaries.
- **Dispute Resolution:** Allows the conductor to scan a passenger's QR receipt to look up the corresponding transaction record for fare dispute verification.
- **Sync Reconciliation:** Shows a side-by-side comparison of the Master terminal's SPIFFS log queue and the mobile SQLite sync queue, with a manual "Flush Sync Queue" button to force an immediate cloud upload.
- **Debug Console:** Displays the raw USB serial communication log between the phone and the Master terminal.

## 3.11.2 Authentication and Vehicle Assignment

The mobile application requires conductor authentication before granting access to the dashboard. The login process communicates with a REST API endpoint to validate the conductor's credentials and receive an authorization token. Upon successful authentication, the application stores the token securely and retrieves the conductor's company affiliation, employee identifier, and assigned vehicle. If no vehicle assignment exists, the conductor is directed to a vehicle selection screen before the shift can begin.

The authorization token is attached to every subsequent cloud API request, ensuring that uploaded transaction records are attributable to the authenticated conductor and their assigned vehicle.

## 3.11.3 Company Web Dashboard (Cloud)

The company web dashboard is a browser-based portal that aggregates data from all active conductors and vehicles within a fleet. It queries the same cloud database that receives uploads from the mobile sync service. The dashboard provides the following views for transit company dispatchers and accounting officers:

**Table 3.10b. Company Dashboard Features**

| Feature | Description |
| :---- | :---- |
| Fleet Occupancy Overview | Real-time display of each bus's current occupancy rate as a ratio of deployed Slave terminals to maximum capacity. |
| Passenger Manifest | Searchable list of all boarding records across the fleet, filterable by vehicle, conductor, route, date, and passenger type. |
| Fare Transaction Records | Transaction-level audit log showing timestamp, boarding stop, destination stop, base fare, discount amount, final fare, Slave terminal UID, and cloud sync status. |
| Shift Reports | Automated per-shift summaries showing total passengers boarded, total revenue collected, fare breakdown by passenger type, and number of proximity alarms triggered. |
| Revenue Reconciliation | Comparison of expected digital revenue (computed from recorded fares and discounts) against the conductor's declared cash collection, with automatic discrepancy flagging. |
| Synchronization Status | Dashboard-wide indicator showing the total number of pending (unsynced) records across all active vehicles, updated as mobile devices upload their queues. |

## 3.11.4 Data Flow — Mobile to Dashboard

The complete data flow from a passenger boarding event to the company dashboard follows this path:

1. The conductor taps a Slave terminal with the Master via NFC.
2. The Master transmits the boarding record to the phone over USB CDC serial.
3. The phone immediately commits the record to the local encrypted SQLite database.
4. The sync service creates a JSON event payload and writes it to the `occupancy_events` table.
5. If the device is online, the sync service immediately POSTs the payload to the cloud REST API with the conductor's bearer token.
6. The cloud database stores the event.
7. The company web dashboard queries the cloud database and renders the updated fleet view.
8. If the device is offline, the event remains in the local queue until connectivity returns, at which point steps 5–7 proceed automatically.

Suggested Figures:
- Figure 3.11. TOMS Mobile Dashboard — Main Interface
- Figure 3.12. Active Passengers Panel with Occupancy Bar
- Figure 3.13. Sync Reconciliation Screen — Master vs. Mobile Queue Comparison
