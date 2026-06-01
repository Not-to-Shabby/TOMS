# Technical Background

This chapter presents the technical background underlying the Transportation Occupancy Monitoring System (TOMS). It discusses the technologies, standards, and architectural concepts employed in the system — explaining what each technology is, why it is relevant to the problem domain, and how it supports the proposed solution.

---

## 3.1 Overview of Automated Fare Collection Systems

Automated Fare Collection (AFC) systems replace manual ticketing and cash handling in public transportation with electronic mechanisms for passenger identification, fare computation, and revenue recording. Early AFC deployments used magnetic-stripe tickets and token dispensers in metro rail networks; contemporary systems integrate contactless smart cards, mobile payments, and real-time cloud dashboards (Suvarna et al., 2022; Kulange et al., 2025).

AFC systems are relevant to TOMS because the Iligan City minibus sector still relies on the traditional "konduktor" model — a human conductor who manually counts passengers, calculates fares, and collects cash — a method that is demonstrably susceptible to human error, revenue leakage, and the absence of verifiable transaction records (Kannan et al., 2020; Ingabire, 2023). Research indicates that transitioning to automated systems can reduce transit operating costs by up to 30% and eliminate the "Human Error Fallacy" inherent in manual manifest generation (Adebiyi et al., 2021; Lam et al., 2026).

In the TOMS architecture, automation is achieved through a handheld Point-of-Sale (POS) terminal that records every boarding event as a structured digital record, calculates the distance-based fare from GPS coordinates, issues a QR-coded receipt on a passenger-held Slave terminal, and uploads all transactions to a cloud dashboard when connectivity is available.

---

## 3.2 Radio Frequency Identification Technology

Radio Frequency Identification (RFID) is a family of wireless technologies that use electromagnetic fields to automatically identify and track objects via embedded tags. The tag stores an identifier and, in some variants, additional data; a reader generates a radio field that powers or communicates with the tag without requiring a direct line of sight. RFID operates across several frequency bands: low-frequency (125–134 kHz), high-frequency (13.56 MHz, governed by ISO/IEC 14443 and ISO/IEC 18000-3), and ultra-high-frequency (860–960 MHz).

Near Field Communication (NFC) is a short-range, high-frequency subset of RFID operating at 13.56 MHz within a maximum range of four centimetres. Its extremely short interaction range acts as a physical security barrier, ensuring that only intentional, close-contact taps register a transaction. The NFC-DEP (Data Exchange Protocol), standardised by ISO/IEC 18092 (NFCIP-1), extends NFC beyond tag reading by enabling bidirectional peer-to-peer data exchange between two active devices — an Initiator and a Target — at speeds up to 424 kbps.

The PN532, manufactured by NXP Semiconductors, is a highly integrated NFC controller chip supporting ISO 14443, ISO 18092, and FeliCa protocols. It can operate as an NFC Initiator, Target, or card emulator, and interfaces with a host microcontroller over I2C, SPI, or UART. In TOMS, one PN532 is fitted to the Master handheld (configured as NFC-DEP Initiator) and one to each Slave terminal (configured as NFC-DEP Target). When the conductor taps the Master against a Slave, the Master transmits a compact boarding record; the Slave responds with an acknowledgment. The Slave's eFuse MAC address is embedded in its 10-byte NFCID3, providing implicit device authentication on every tap without a separate UID exchange.

---

## 3.3 Reusable RFID Seat Card Concept

Most reviewed literature assumes that each passenger owns a personal smart card that stores a balance or travel history (Pijdurkar et al., 2024; Agarwal et al., 2022). This model is impractical for high-turnover Philippine minibuses where passengers board without pre-registration and conductors must handle dozens of transactions per shift.

The reusable RFID seat card concept addresses this gap by treating the NFC-capable Slave terminal itself as a temporary, reusable receipt token. Rather than a passenger owning a card, the conductor holds a pool of Slave terminals and hands one to each boarding passenger. The Slave displays the fare, generates a QR receipt, and monitors proximity to the passenger's destination stop. When the passenger alights, the conductor retrieves the Slave and resets it for the next passenger.

This approach eliminates card registration, card top-up infrastructure, and the card-distribution problem entirely. It aligns with the reusable seat-marker concept identified as a research gap in the systematic literature review (Chapter II), where fixed-gate metro studies (Suvarna et al., 2022; Zaman et al., 2023) offer no guidance for mobile, unscheduled transit operations.

---

## 3.4 Internet of Things in Transportation Systems

The Internet of Things (IoT) is the paradigm of connecting physical devices to each other and to the internet to enable autonomous data collection, real-time monitoring, and remote control. In transportation, IoT architectures typically combine on-board microcontrollers, wireless communication modules, GPS receivers, and cloud databases to provide fleet operators with live visibility into vehicle locations, passenger counts, and revenue flows (Nirmala et al., 2024; Kulange et al., 2025).

ESP-NOW, developed by Espressif Systems, is a lightweight, connectionless IoT wireless protocol that transmits packets of up to 250 bytes directly between paired ESP32 devices over the 2.4 GHz Wi-Fi physical layer — without a router, access point, or IP stack — delivering messages in under 10 milliseconds. This sub-second latency makes it suitable for real-time event relay inside a moving vehicle where standard Wi-Fi association (which takes several seconds) and Bluetooth Low Energy pairing (which requires a ceremony) are impractical.

In TOMS, ESP-NOW carries all asynchronous in-vehicle wireless events: payment button-press notifications from Slave to Master, alarm commands from Master to Slave, heartbeat packets confirming Slave power status, and boarding confirmation events. Its operation is entirely independent of mobile internet connectivity, ensuring that the core boarding workflow functions in all cellular dead zones along the Dalipuga–DPWH route.

---

## 3.5 ESP32 Microcontroller

The ESP32-S3 is a dual-core, 32-bit system-on-chip (SoC) manufactured by Espressif Systems. It integrates a 240 MHz Xtensa LX7 processor, 520 KB of internal SRAM, Wi-Fi and Bluetooth Low Energy radios, a native USB controller, and hardware-accelerated cryptographic engines into a single package. Factory-burned electronic fuses (eFuses) store a globally unique 48-bit MAC address that cannot be altered, providing each terminal with a permanent, tamper-proof hardware identity embedded into every boarding transaction.

The Espressif IoT Development Framework (ESP-IDF) is the official C-based software development framework for the ESP32 family. It provides hardware abstraction layers, peripheral drivers, networking stacks, and a bundled FreeRTOS real-time operating system kernel that offers priority-based preemptive task scheduling, inter-task message queues, and synchronisation primitives. The dual-core architecture allows time-critical radio operations (NFC-DEP and ESP-NOW) to execute on Core 1 while display updates, file system writes, and USB serial communication run on Core 0, preventing any single task from blocking or delaying the rest.

PlatformIO, a cross-platform build system, manages ESP-IDF projects through a declarative configuration file. In TOMS, it links a shared library — containing the communication protocol, CRC utilities, ESP-NOW drivers, and NFC interface — into both the Master and Slave firmware projects from a single source directory, ensuring protocol compatibility and preventing code duplication across devices.

---

## 3.6 GPS-Based Route Tracking and Fare Calculation

The Global Positioning System (GPS) is a satellite-based navigation system providing geographic coordinates (latitude and longitude) and timestamps to receivers anywhere on Earth. In transit applications, GPS enables distance-based fare computation by recording where a passenger boards and where they alight, automatically deriving the fare from a lookup table keyed to route segments (Rajkumar et al., 2018; Zaman et al., 2023; Suthagar, 2023).

In TOMS, GPS coordinates are read from the Android phone's built-in GNSS receiver via the Flutter `geolocator` package at five-second intervals. The boarding stop is determined by computing the Haversine distance between the phone's current position and every stop in the `stops.json` asset file; the nearest stop within a configurable threshold is selected. The fare is then calculated from the base rate and the road distance between the boarding and destination stops using the route's fare table.

Proximity geofencing is a location-based trigger mechanism that fires an event when the tracked vehicle crosses a defined geographic boundary around a target transit stop. TOMS continuously compares the phone's GPS coordinates against the saved destination coordinates of every active unpaid passenger. When the computed distance falls below 200 metres, the alarm sequence is initiated — the mobile application vibrates and displays a banner, and the Master broadcasts a red-screen alarm command to the relevant Slave via ESP-NOW. A compact fare dictionary further compresses boarding payloads: a shared one-byte index maps to the full fare amount, reducing the NFC-DEP payload from 40 bytes to 6 bytes and minimising the required tap duration.

---

## 3.7 Offline-First Data Storage

Offline-first is an architectural pattern in which an application writes every transaction to local persistent storage immediately upon occurrence, treating remote server synchronisation as a secondary, asynchronous process. This ensures that data is never lost due to network unavailability and that the application remains fully functional regardless of connectivity.

SPIFFS (SPI Flash File System) is an open-source, flat file system designed for SPI NOR flash memory with built-in wear-levelling that distributes writes evenly across flash sectors. In TOMS, the Master ESP32-S3 logs every boarding event to its internal SPIFFS partition at the path `/storage` as soon as the event is confirmed. If the conductor's phone is disconnected mid-shift, the complete trip manifest is preserved in the Master's local storage.

SQLite is a self-contained, serverless relational database engine embedded directly into the Flutter mobile application binary. Every boarding event received from the Master over USB is committed to a local SQLite database with a `synced = 0` flag. A `passenger_logs` table records the timestamp, boarding type, fare in centavos, boarding and destination stops, passenger type, discount, slot number, Slave UID, and vehicle identifier for each transaction. The combination of SPIFFS on the Master and SQLite on the phone ensures that no event is lost even under total connectivity failure.

---

## 3.8 Data Synchronisation

Store-and-forward synchronisation is the pattern by which records written to local storage are later forwarded to a remote server when a viable network path is detected. A background service monitors connectivity and drains the local queue in chronological order when internet access is restored.

In TOMS, the `SyncService` in the Flutter application follows a real-time-first, cache-as-fallback strategy. When internet connectivity is available, each occupancy event (boarding, payment, alarm, release) is immediately HTTP-POSTed to the cloud backend after being written to SQLite. When connectivity is absent, the record remains in the SQLite queue with `synced = 0`. A `ConnectivityService`, powered by the `connectivity_plus` Flutter package, monitors network state and invokes `SyncService.flushQueue()` upon reconnection, uploading all pending records oldest-first and marking each successfully transmitted row as `synced = 1`. A 30-second retry timer ensures the flush is reattempted if the initial reconnection attempt fails. Supabase, an open-source backend-as-a-service built on PostgreSQL, serves as the central cloud repository. The Flutter application pushes boarding records to Supabase via REST API calls; the company dashboard queries the same PostgreSQL database to display live fleet occupancy, audit logs, and revenue reports.

---

## 3.9 Web-Based Dashboard for Passenger and Fare Monitoring

A web-based dashboard is a browser-accessible interface that aggregates data from multiple sources — in this case, multiple buses — and presents it as real-time charts, tables, and status indicators accessible to transport operators without installing proprietary software.

The TOMS company dashboard is hosted on a local server at the transport cooperative's office and queries the Supabase PostgreSQL backend. It provides three primary views. The Fleet Overview displays a table of active buses with their route, assigned conductor, current occupancy expressed as `slavesDeployed / maxCapacity`, today's revenue, and a connectivity status badge (Live when receiving real-time events; Cached when the phone is offline and showing last-known occupancy). The Transaction Audit Log provides a searchable, filterable record of every boarding event — by date, bus, route, passenger type, and Slave UID — replacing the manual paper-ticket collection performed by dispatchers at 18:00 each day. The Revenue Analytics view shows daily and weekly revenue bar charts broken down by passenger type (Regular, Student, PWD, Senior), supporting shift reconciliation by comparing the system's computed expected revenue against the conductor's declared cash collection and flagging any shortfall automatically.

---

## 3.10 Electronic Passenger Manifest

An electronic passenger manifest is an automated, real-time digital record of all passengers currently aboard a transit vehicle, including their boarding stop, destination stop, fare, passenger type, payment status, and the unique identifier of their assigned Slave terminal.

Traditional minibus operations produce no manifest at all; dispatchers rely on paper tickets collected at end-of-shift to reconstruct ridership. The TOMS system generates a live manifest on the conductor's Android phone dashboard that mirrors the exact occupancy state of the vehicle at any given moment. Each entry in the manifest corresponds to an active `PassengerSlot` — a data structure holding the slot number (boarding order), Slave UID, boarding stop coordinates, destination stop, fare, discount, passenger type, payment status, and alarm state. The manifest persists in SQLite and is continuously updated as passengers pay or alight. When the vehicle returns to the terminal and internet connectivity is restored, the complete shift manifest is uploaded to Supabase, providing the dispatcher with a full, timestamped record of every transaction without any manual data entry.

---

## 3.11 Passenger Occupancy Monitoring

Passenger occupancy monitoring is the real-time tracking of the number of passengers aboard a transit vehicle, expressed as a ratio of current occupants to the vehicle's rated capacity. Accurate occupancy data enables dispatchers to optimise fleet deployment, prevent overloading, and identify revenue-per-seat efficiency metrics (Murdan et al., 2020; Gonzales et al., 2025).

In Philippine public utility vehicles, passengers board at any available position rather than pre-assigned seats, and boarding and alighting points are not standardised. This requires an occupancy model that does not depend on fixed seat assignments. TOMS uses a dynamic slot assignment model: each boarding passenger is assigned a sequential slot number (1, 2, 3, …) in the order they board, resetting to 1 at the start of each trip. Occupancy is computed as `slavesDeployed ÷ maxCapacity`, where `slavesDeployed` is the count of active, unrecovered Slave terminals and `maxCapacity` is the vehicle's configured seating limit.

Each active slot transitions through three states. **Active (Unpaid):** the Slave has been assigned and displays the fare; payment has not been collected. **Paid:** the passenger pressed the Slave's button and the conductor confirmed cash receipt. **Alarming:** the vehicle is within 200 metres of the passenger's registered destination and payment remains outstanding; the app vibrates and broadcasts a red-screen alarm to the Slave.

To avoid perceptible UI delay, TOMS uses a two-phase optimistic transaction model. In Phase 1 (Optimistic), the Master immediately notifies the phone upon detecting an NFC tap, and the app increments the occupancy count. In Phase 2 (Confirmed), the Slave sends a `PASSENGER_BOARD` event via ESP-NOW after processing the boarding command, finalising the transaction record. This ensures the conductor's dashboard reflects new boardings instantly while the confirmed record arrives asynchronously.

---

## 3.12 Digital Fare Recording and Revenue Leakage Reduction

Digital fare recording is the automatic capture of each fare transaction as a structured, immutable electronic record containing the amount collected, the passenger's boarding and alighting points, the timestamp, the passenger type, and any applicable discount. Revenue leakage — the gap between theoretical fare revenue and actual cash collected — is a persistent problem in manually operated transit systems caused by uncollected fares, counting errors, and fraudulent under-reporting (Zaman et al., 2023; Ingabire, 2023).

TOMS records every fare transaction in SQLite with the base fare, discount amount, final fare, and passenger type (Regular, Student, PWD, Senior). Philippine law mandates a 20% discount for Senior Citizens (RA 9994), Persons with Disability (RA 10754), and students on qualifying routes. The system applies these discounts automatically based on the passenger type selected by the conductor. A QR code receipt encoding the vehicle identifier, timestamp, fare, and Slave UID is displayed on the Slave's LCD screen immediately after boarding, providing passengers with a verifiable, digital proof of payment that replaces the paper ticket entirely.

At the dashboard level, the system computes the expected shift revenue from all recorded transactions and compares it against the conductor's declared cash declaration. Any shortfall is flagged automatically, providing dispatchers with an objective basis for discrepancy resolution without physical ticket counting or manual arithmetic.

---

## 3.13 Portable Point-of-Sale System

A Portable Point-of-Sale (POS) terminal is a compact, battery-powered device used by a mobile agent — in this context, the conductor — to process transactions in the field without a fixed counter or wired infrastructure. The portability requirement imposes constraints on form factor, power consumption, processing capacity, and communication interfaces.

The TOMS Master terminal uses the ESP32-S3 SoC (Section 3.5) as its processing core. It communicates with the conductor's Android phone via a USB CDC-ACM (Communications Device Class – Abstract Control Model) virtual serial port implemented by the TinyUSB firmware stack over the ESP32-S3's native USB-C hardware. The CDC-ACM standard ensures that the phone recognises the device using built-in class drivers without additional software installation. Boarding events are streamed to the phone as newline-delimited JSON lines at 115,200 baud.

The Slave terminal's display is driven by an ST7735S single-chip TFT LCD controller operating a 1.8-inch, 128×160 pixel colour panel over a 4-wire SPI interface. The LVGL (Light and Versatile Graphics Library) graphics library renders six screen states — Welcome, Processing, Fare, QR Receipt, Alarm, and Sleep — through a state machine. QR codes encoding the transaction summary are rendered directly onto an LVGL canvas widget using an embedded encoder library.

The Flutter mobile application, compiled to native ARM machine code for Android, serves as the conductor's dashboard. The Provider state management library propagates changes from the USB service, database service, and GPS service to the UI independently, ensuring that a new boarding event updates the occupancy count without redrawing unrelated UI elements. A `usb_serial` Flutter plugin wraps Android's USB host API, accumulating incoming bytes into a line-based parser and dispatching each complete JSON event to the appropriate service.

---

## 3.14 Network Dead Zones and Connectivity Challenges

Network dead zones are geographic areas where mobile cellular signal is weak or entirely absent, causing standard IoT architectures that rely on continuous cloud connectivity to fail silently — dropping transaction data or halting operation (Kulange et al., 2025; Nirmala et al., 2024). In Iligan City, field survey data identifies several documented dead zones along active minibus routes, including stretches near Tag-ibo, Sta. Fe, Barinaut, and Timoga, where signal loss can last several minutes per trip.

The challenge is compounded in moving vehicles: the vehicle may enter and exit dead zones multiple times per shift, so any synchronisation strategy that waits for a stable connection before recording data risks losing entire segments of the trip manifest. Standard approaches such as "sync at the terminal" fail when the vehicle makes multiple round trips per shift or when the dispatcher requires live occupancy data rather than end-of-day batch uploads.

TOMS addresses this with the offline-first, real-time-push architecture described in Sections 3.7 and 3.8: transactions are committed to local storage first, pushed to the server immediately when connectivity exists, and flushed automatically upon reconnection. The ESP-NOW radio link (Section 3.4) between the Master and Slave terminals is entirely independent of cellular infrastructure, ensuring that the core boarding workflow — tap, fare display, QR receipt, payment notification, proximity alarm — operates without interruption throughout the dead zones.

---

## 3.15 Data Security and Transaction Integrity

Data security in transit POS systems encompasses three concerns: preventing unauthorised command injection (a fraudulent device sending boarding commands to a Slave), preventing eavesdropping on transaction data transmitted over open radio channels, and ensuring that stored records are not corrupted by electromagnetic interference or partial writes.

TOMS applies security at three layers. At the hardware authentication layer, every `BOARD_COMMAND` packet contains a 16-byte `target_uid` field that must exactly match the receiving Slave's eFuse MAC address; mismatched packets are silently discarded. In the NFC path, authentication is implicit because the Slave's NFCID3 — captured during the NFC-DEP handshake — contains its eFuse MAC. At the link encryption layer, ESP-NOW applies CCMP (Counter Mode with CBC-MAC Protocol, based on AES-128), with a pre-shared 16-byte Local Master Key ensuring that every wireless frame is encrypted and integrity-protected; replayed or tampered frames fail the CCMP check and are discarded. At the application encryption layer, sensitive payload fields may additionally be protected with AES-256-CBC via the mbedTLS library, with keys stored in the ESP32-S3's Non-Volatile Storage (NVS) flash partition.

Packet integrity across all communication channels (NFC-DEP, ESP-NOW, and USB CDC-ACM) is enforced by a CRC-16-CCITT checksum appended to every `toms_packet_t` frame. The generator polynomial *x¹⁶ + x¹² + x⁵ + 1* detects all single-bit and double-bit errors, all odd numbers of errors, and all burst errors of length 16 or less — coverage sufficient for the electromagnetic environment of a moving metal vehicle.

---

## 3.16 Summary of Technical Background

This chapter reviewed the foundational technologies underpinning the Transportation Occupancy Monitoring System. Section 3.1 established the context of automated fare collection and its demonstrated benefits over manual methods. Section 3.2 introduced RFID and NFC-DEP as the contactless tap interface between the Master and Slave terminals. Section 3.3 defined the reusable seat-card concept that eliminates personal card ownership. Section 3.4 described the IoT communication layer, particularly ESP-NOW, which enables real-time in-vehicle event relay independent of cellular infrastructure. Section 3.5 detailed the ESP32-S3 SoC and its firmware framework. Section 3.6 explained GPS-based fare calculation and proximity geofencing for the destination-alarm feature. Sections 3.7 and 3.8 defined the offline-first storage and store-and-forward synchronisation pattern that guarantees data integrity across dead zones. Section 3.9 described the web-based company dashboard that aggregates fleet-wide occupancy and revenue data. Section 3.10 introduced the electronic passenger manifest as the live, on-device record of current occupants. Section 3.11 detailed the dynamic slot assignment occupancy model suited to unscheduled Philippine transit. Section 3.12 covered digital fare recording and the automatic revenue reconciliation mechanism. Section 3.13 described the portable POS hardware and mobile application stack. Section 3.14 analysed network dead zones as a primary design constraint, and Section 3.15 reviewed the three-layer security architecture protecting command integrity and data confidentiality.

Together, these technologies form a coherent, mutually reinforcing system architecture in which each component fills a specific gap identified in the systematic literature review: ESP-NOW replaces cellular dependency for in-vehicle events; NFC-DEP replaces mechanical pogo-pin docking; dynamic slot assignment replaces fixed-seat RFID gate models; and the offline-first sync pattern replaces end-of-day batch uploads with continuous, resilient data transmission.

---

## Definition of Terms

| Term | Definition |
|------|-----------|
| **AES (Advanced Encryption Standard)** | A symmetric block cipher standardised by NIST in FIPS 197, used at 128-bit key length for ESP-NOW link encryption and 256-bit for application-layer payload protection. |
| **AFC (Automated Fare Collection)** | Electronic systems that replace manual cash-and-ticket fare collection with contactless identification, automatic fare computation, and digital receipts. |
| **CCMP (Counter Mode with CBC-MAC Protocol)** | An authenticated encryption protocol defined in IEEE 802.11i that combines AES-128 with a message authentication code, applied to all ESP-NOW radio frames in TOMS. |
| **CDC-ACM (Communications Device Class – Abstract Control Model)** | A USB device class that allows the Master ESP32-S3 to present itself as a virtual serial port to the Android phone without requiring custom driver installation. |
| **CRC-16-CCITT** | A 16-bit cyclic redundancy check using the polynomial *x¹⁶ + x¹² + x⁵ + 1*, appended to every TOMS data packet to detect transmission errors. |
| **eFuse (Electronic Fuse)** | One-time programmable memory cells in the ESP32-S3 storing a unique 48-bit MAC address that cannot be altered, serving as a tamper-proof hardware identity. |
| **ESP-IDF (Espressif IoT Development Framework)** | The official C-based development framework for ESP32 microcontrollers, providing peripheral drivers, networking stacks, and FreeRTOS integration. |
| **ESP-NOW** | A connectionless, low-overhead wireless protocol by Espressif that transmits packets between paired ESP32 devices over 2.4 GHz without a router, in under 10 milliseconds. |
| **ESP32-S3** | A dual-core 32-bit Xtensa LX7 SoC by Espressif Systems with integrated Wi-Fi, Bluetooth, native USB, and hardware cryptographic acceleration. |
| **Flutter** | An open-source UI framework by Google that compiles Dart code to native ARM machine code, used for the TOMS conductor dashboard. |
| **FreeRTOS** | An open-source real-time operating system kernel providing priority-based preemptive task scheduling, bundled with ESP-IDF. |
| **Geofencing** | A location-monitoring mechanism that triggers an event when a tracked object crosses a virtual boundary around a geographic coordinate, used to detect when unpaid passengers approach their destination. |
| **GPS (Global Positioning System)** | A satellite-based navigation system providing geographic coordinates, used in TOMS to determine the boarding stop and compute proximity alarms. |
| **IoT (Internet of Things)** | The paradigm of connecting physical devices to each other and to the internet for autonomous data collection, monitoring, and control. |
| **LVGL (Light and Versatile Graphics Library)** | An open-source embedded graphics library providing widgets and font rendering for microcontrollers, used to render fare screens and QR receipts on the Slave LCD. |
| **NFC (Near Field Communication)** | A short-range (under 4 cm) wireless technology at 13.56 MHz, used as the tap interface between the Master and Slave terminals. |
| **NFC-DEP (NFC Data Exchange Protocol)** | The peer-to-peer data exchange protocol within ISO/IEC 18092 enabling bidirectional communication between an Initiator and a Target during an NFC session. |
| **NVS (Non-Volatile Storage)** | An ESP-IDF key-value store that persists configuration data and encryption keys in a dedicated flash partition across power cycles. |
| **Offline-First** | An architectural pattern in which all data is written to local storage immediately, with server synchronisation treated as an asynchronous secondary process. |
| **PassengerSlot** | A TOMS data model representing one boarding passenger, holding their slot number, Slave UID, boarding stop, destination, fare, discount, passenger type, payment status, and alarm state. |
| **PlatformIO** | A cross-platform embedded build system that manages toolchain configuration and dependency resolution for ESP-IDF firmware projects. |
| **PN532** | An NXP Semiconductors NFC controller chip supporting ISO 14443 and ISO 18092, capable of operating as an Initiator or Target, used in both Master and Slave terminals. |
| **Provider** | A Flutter state management library that propagates data changes from services to UI widgets, enabling targeted rebuilds without full-screen redraws. |
| **QR Code (Quick Response Code)** | A two-dimensional matrix barcode with Reed-Solomon error correction, rendered on the Slave display as a digital receipt containing the transaction summary. |
| **Revenue Leakage** | The financial loss in transit systems caused by uncollected fares, counting errors, and the absence of verifiable transaction records. |
| **RFID (Radio Frequency Identification)** | A family of wireless technologies using radio waves to identify objects via embedded tags; NFC is a short-range, high-frequency subset of RFID. |
| **SPIFFS (SPI Flash File System)** | A wear-levelled flat file system for SPI NOR flash, used on the Master for persistent offline transaction logging. |
| **SQLite** | A self-contained, serverless relational database engine embedded in the TOMS mobile application for offline-first transaction caching. |
| **ST7735S** | A Sitronix single-chip TFT LCD controller driving the Slave terminal's 1.8-inch, 128×160 pixel colour display over SPI. |
| **Store-and-Forward** | A data transmission pattern where records are committed to local storage immediately and uploaded to a remote server when connectivity becomes available. |
| **Supabase** | An open-source backend-as-a-service platform built on PostgreSQL, serving as the central cloud repository for fleet-wide occupancy and revenue data. |
| **TinyUSB** | An open-source USB device stack used by the Master firmware to expose a CDC-ACM virtual serial interface to the connected Android phone. |
