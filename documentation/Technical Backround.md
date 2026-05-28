# Technical Background

This chapter presents the technical background underlying the Transportation Occupancy Monitoring System (TOMS). It discusses the technologies, standards, and architectural concepts employed in the system, explaining what each technology is, why it is relevant to the problem domain, and how it supports the proposed solution.

---

## 1. Embedded Systems and Microcontroller Architecture

### 1.1 ESP32-S3 System-on-Chip

The ESP32-S3 is a dual-core, 32-bit system-on-chip (SoC) manufactured by Espressif Systems. It integrates a 240 MHz Xtensa LX7 processor, 520 KB of internal SRAM, Wi-Fi and Bluetooth Low Energy radios, a native USB controller, and hardware-accelerated cryptographic engines into a single package. The chip also contains factory-burned electronic fuses (eFuses) — one-time programmable memory cells that store a globally unique 48-bit MAC address that cannot be altered or erased after manufacturing.

The ESP32-S3 is relevant to TOMS because the system requires a microcontroller that can concurrently manage wireless communication, display rendering, battery monitoring, and data logging within a compact, low-power form factor capable of surviving a full transit operating shift. The dual-core architecture allows time-critical radio operations (NFC and ESP-NOW) to execute on one core while display updates, file system writes, and serial communication run independently on the other, preventing any single task from blocking or delaying the rest.

In the TOMS architecture, the ESP32-S3 serves as the processing core for both the Master and Slave terminals. The Master terminal uses it to coordinate NFC-DEP handshakes, ESP-NOW radio reception, USB serial streaming to the phone, and flash-based log persistence. The Slave terminal uses it to drive the LCD display, render QR code receipts, manage deep-sleep power cycling, and listen for wireless alarm commands. The eFuse MAC address provides each terminal with a permanent, tamper-proof hardware identity that is embedded into every boarding transaction, preventing device cloning or command spoofing.

### 1.2 ESP-IDF and FreeRTOS

The Espressif IoT Development Framework (ESP-IDF) is the official C-based software development framework for the ESP32 family. It provides hardware abstraction layers, peripheral drivers, networking stacks, a build system, and a component manager for integrating third-party libraries. ESP-IDF bundles FreeRTOS — an open-source, preemptive real-time operating system kernel that provides priority-based task scheduling, inter-task message queues, and synchronization primitives.

ESP-IDF is relevant because the ESP32-S3's hardware peripherals — including its USB controller, I2C bus, SPI bus, ADC channels, and Wi-Fi radio — cannot be accessed through generic C libraries. A chip-specific framework is required. FreeRTOS is relevant because both TOMS terminals must run multiple concurrent operations with strict timing requirements: the Master simultaneously handles NFC polling, radio packet reception, USB serial output, and flash file management, while the Slave simultaneously manages display rendering, button input monitoring, NFC target listening, and radio communication. A single-threaded execution model cannot satisfy these constraints without dropping packets or introducing perceptible interface lag.

In the TOMS firmware, both the Master and Slave create multiple FreeRTOS tasks pinned to specific processor cores with assigned priority levels. Latency-sensitive radio handlers are pinned to Core 1 at the highest priorities, while display, storage, and logic tasks run on Core 0 at lower priorities. This core-affinity scheduling guarantees that a wireless event from a passenger's button press is never delayed by a screen redraw or a file write.

### 1.3 PlatformIO Build System

PlatformIO is a cross-platform build system and IDE extension that manages ESP-IDF projects through a unified configuration file. It abstracts the Xtensa GCC toolchain installation, Python environment setup, and ESP-IDF component resolution into a declarative format.

PlatformIO is relevant because it enables reproducible firmware builds across development machines without manual toolchain configuration. In the TOMS project, PlatformIO links a shared library — containing the communication protocol, cryptographic routines, ESP-NOW drivers, and NFC interface — into both the Master and Slave firmware projects from a single source directory, preventing code duplication and ensuring protocol compatibility between devices.

---

## 2. Near Field Communication (NFC)

### 2.1 NFC and RFID Fundamentals

Near Field Communication (NFC) is a short-range wireless technology operating at 13.56 MHz within the high-frequency band of the Radio Frequency Identification (RFID) spectrum. NFC interactions occur at distances of four centimeters or less, requiring deliberate physical proximity between two devices. The technology is standardized under ISO/IEC 18000-3 for the air interface and ISO/IEC 14443 for proximity card protocols.

NFC is relevant to TOMS because the system requires a contactless mechanism for registering boarding events. Previous iterations of the system used physical pogo-pin docking connectors, which degraded rapidly from repeated mechanical contact in the vibration-heavy environment of a moving minibus. NFC eliminates mechanical wear entirely. The extremely short interaction range serves as a physical security barrier, ensuring that only intentional, close-contact taps register a boarding event and preventing accidental reads from adjacent passengers. The tap gesture is also familiar to Filipino commuters through everyday ATM and mobile wallet transactions, requiring no operator training.

### 2.2 NFC-DEP Peer-to-Peer Protocol (ISO/IEC 18092)

The NFC Data Exchange Protocol (NFC-DEP) is a peer-to-peer communication mode defined by ISO/IEC 18092, also known as NFCIP-1. Unlike standard NFC tag reading, which only retrieves static data from passive cards, NFC-DEP enables bidirectional data exchange between two active devices — an Initiator and a Target. The protocol governs speed negotiation (106 kbps or 424 kbps), collision resolution, and data framing.

NFC-DEP is relevant because TOMS requires the Master terminal to transmit dynamic boarding configurations (fare, destination, slot assignment) to the Slave terminal and receive an acknowledgment in return. A unidirectional tag-reading model cannot support this interaction. By conforming to ISO/IEC 18092, both PN532 transceiver modules — one in the Master and one in the Slave — execute an identical Initiator-Target handshake regardless of manufacturing batch, ensuring reliable tap interactions in a moving, vibration-prone vehicle.

In the TOMS architecture, the Master operates as the NFC-DEP Initiator. Upon detecting a Slave in proximity, it transmits a compact boarding record — consisting of a fare index, slot number, and timestamp — compressed via a shared fare dictionary to minimize the data payload. The Slave, operating as the NFC-DEP Target, receives this record and responds with an acknowledgment. The Slave's hardware eFuse MAC address is embedded in its 10-byte NFC identity field (NFCID3), providing implicit device authentication with every tap.

### 2.3 PN532 NFC Transceiver

The PN532, manufactured by NXP Semiconductors, is a highly integrated NFC controller chip supporting ISO 14443, ISO 18092, and FeliCa protocols. It can operate as an NFC Initiator, Target, or card emulator, and interfaces with a host microcontroller over I2C, SPI, or UART.

The PN532 is relevant because the ESP32-S3 does not contain integrated NFC RF hardware. A dedicated transceiver is required to generate the 13.56 MHz field, execute NFC-DEP protocol framing, and signal the microcontroller upon detecting a target device. In TOMS, the Master's PN532 is configured as an Initiator that actively polls for nearby Targets, while the Slave's PN532 is configured as a passive Target that waits for an Initiator connection. An interrupt-driven design — using the PN532's active-low IRQ output — allows the microcontroller to process other tasks until an NFC event occurs, rather than consuming CPU cycles in a blocking poll loop.

---

## 3. Wireless Communication

### 3.1 ESP-NOW Protocol

ESP-NOW is a proprietary, connectionless wireless protocol developed by Espressif Systems. It transmits small data packets (up to 250 bytes) directly between paired devices over the 2.4 GHz Wi-Fi physical layer without requiring a router, access point, or IP address assignment. Packets are delivered in under 10 milliseconds.

ESP-NOW is relevant because standard Wi-Fi association takes several seconds to complete — too slow for real-time relay of passenger button presses or proximity alarms. Bluetooth Low Energy requires a pairing ceremony that is impractical in a dynamic fleet of interchangeable terminals. ESP-NOW delivers instant, connectionless communication between terminals that may be powered on and off independently throughout the day.

In the TOMS system, ESP-NOW handles all asynchronous in-vehicle wireless events. When a passenger presses the button on their Slave terminal, a payment notification is transmitted to the Master via ESP-NOW. The Master forwards this event to the mobile app over USB. In the reverse direction, when the mobile app detects that an unpaid passenger is approaching their destination, the Master broadcasts an alarm command to the target Slave via ESP-NOW, triggering a red-screen warning. ESP-NOW also carries periodic heartbeat packets from Slave terminals, allowing the Master to passively track which devices are still powered and within radio range.

### 3.2 Encryption: AES and CCMP

The Advanced Encryption Standard (AES) is a symmetric block cipher adopted as the international encryption standard by the National Institute of Standards and Technology (NIST) under FIPS 197. It operates on fixed 128-bit data blocks with key sizes of 128, 192, or 256 bits. The Counter Mode with CBC-MAC Protocol (CCMP), defined in IEEE 802.11i, is an authenticated encryption protocol built on AES-128 that provides both data confidentiality and message integrity verification.

Encryption is relevant because TOMS boarding records — containing fare amounts, device identifiers, and transaction timestamps — are transmitted over open 2.4 GHz radio inside a public vehicle. Without encryption, any device with a compatible receiver can capture, read, or forge these transmissions.

TOMS applies encryption at two layers. At the link layer, ESP-NOW uses CCMP (AES-128) with a pre-shared Local Master Key (LMK) to encrypt and authenticate all radio frames between the Master and Slave terminals. Any replayed or tampered frame fails the CCMP integrity check and is silently discarded. At the application layer, the firmware applies AES-256 encryption via the mbedTLS cryptographic library to sensitive payload fields before transmission. The encryption keys are stored in the chip's Non-Volatile Storage (NVS) — a key-value store built into the ESP-IDF framework that persists data across power cycles in a dedicated flash partition. This dual-layer approach prevents packet sniffing, replay attacks, and fraudulent command injection.

---

## 4. USB Communication

### 4.1 USB CDC-ACM

The USB Communications Device Class – Abstract Control Model (CDC-ACM) is a standard device class defined by the USB Implementers Forum that allows an embedded device to present itself as a virtual serial port to a host computer or smartphone. The host operating system recognizes the device using built-in class drivers without requiring additional software installation.

USB CDC-ACM is relevant because the TOMS Master terminal must stream live boarding data to the conductor's Android phone over a physical cable. Custom USB drivers are impractical to distribute and maintain on consumer smartphones. The CDC-ACM standard guarantees that any Android phone with USB On-The-Go (OTG) support can communicate with the Master using generic serial drivers.

### 4.2 TinyUSB Firmware Stack

TinyUSB is an open-source USB device stack that implements the USB protocol layers — device descriptors, endpoint configuration, and class-specific interfaces — in firmware. The Espressif-maintained variant integrates with ESP-IDF, leveraging the ESP32-S3's native USB hardware.

TinyUSB is relevant because the ESP32-S3's USB peripheral requires a software stack to present a functional device class to the host operating system. In TOMS, TinyUSB configures the Master terminal to expose a CDC-ACM virtual serial interface over its USB-C port. Boarding events are streamed to the phone as newline-delimited JSON lines, and the phone sends control commands (such as alarm triggers) in the opposite direction.

---

## 5. Display and User Interface

### 5.1 ST7735S TFT LCD

The ST7735S is a single-chip TFT LCD controller manufactured by Sitronix Technology. It drives a 1.8-inch, 128×160 pixel color display over a 4-wire SPI interface. The controller handles column and row address windowing, color format selection, and pixel memory management internally.

The ST7735S display is relevant because the TOMS Slave terminal must present fare amounts, destination names, slot assignments, and QR code receipts to passengers in a visually readable format. The small form factor and SPI interface are compatible with the size and power constraints of a battery-powered handheld device.

### 5.2 LVGL Graphics Library

The Light and Versatile Graphics Library (LVGL) is an open-source embedded graphics library written in C. It provides a widget system (labels, buttons, canvases, spinners), font rendering with anti-aliasing, double-buffered screen updates, and a display-driver abstraction layer. LVGL is designed for microcontrollers with limited RAM and no GPU.

LVGL is relevant because rendering formatted text, animated transitions, and QR code bitmaps on a 128×160 pixel display requires managed frame buffers, font glyph tables, and widget lifecycle management that cannot be reasonably hand-coded for production use. In the TOMS Slave firmware, LVGL drives six distinct screen states — Welcome, Processing, Fare, QR Receipt, Debug, and Sleep — through a state-machine controlled by the main logic task. The graphics timer runs at 10-millisecond intervals to ensure smooth visual transitions.

### 5.3 QR Code Generation

QR (Quick Response) codes are two-dimensional matrix barcodes that encode data as patterns of dark and light modules, incorporating Reed-Solomon error correction to remain readable even when partially obscured.

QR code generation is relevant because TOMS provides passengers with a digital receipt that can be photographed for record-keeping. The Slave terminal encodes a transaction summary — containing the vehicle identifier, timestamp, fare, and terminal UID — into a QR code rendered directly on the LCD screen using an embedded encoder library and an LVGL canvas widget.

---

## 6. Mobile Application Technologies

### 6.1 Flutter Framework

Flutter is an open-source application framework developed by Google that compiles Dart source code to native ARM machine code for Android and iOS devices. It features an asynchronous runtime with isolate-based concurrency, allowing background tasks to execute independently of the user interface rendering thread.

Flutter is relevant because the TOMS conductor dashboard must simultaneously process incoming USB serial streams, poll GPS coordinates, write to a local database, and render a live occupancy display — all without perceptible frame drops or input lag. Flutter's asynchronous architecture ensures that the occupancy panel and alarm banners update in real time even while the sync service is uploading records to the cloud in the background.

### 6.2 Provider State Management

Provider is a Flutter state management library that implements the InheritedWidget pattern for propagating application state to UI components. When a data source notifies Provider of a state change, only the widgets that depend on that specific data are rebuilt, avoiding unnecessary full-screen redraws.

Provider is relevant because the TOMS dashboard must reflect changes from three independent data sources — USB serial events, local database queries, and device connection status — simultaneously. Provider enables the USB service, database service, and application state classes to notify the UI layer independently, ensuring that a new boarding event from the Master triggers an occupancy count update without disrupting the battery status display or the alarm banner.

### 6.3 USB Serial Plugin

The Flutter framework does not include native access to USB serial ports. The TOMS mobile application uses a platform-channel plugin that wraps Android's USB host API, providing Dart-level access to CDC-ACM virtual COM ports connected via USB OTG. The plugin enumerates available USB devices, opens the serial connection at 115,200 baud (8 data bits, 1 stop bit, no parity), and delivers incoming bytes as a stream that is split on newline delimiters into complete JSON event lines.

---

## 7. Data Persistence and Synchronization

### 7.1 SPIFFS (SPI Flash File System)

SPIFFS is an open-source, flat file system designed for SPI NOR flash memory chips. It includes built-in wear-leveling algorithms that distribute write and erase operations evenly across flash sectors, preventing premature degradation of the physical memory cells.

SPIFFS is relevant because the Master terminal logs hundreds of boarding events per shift to its internal flash memory. Without wear-leveling, the repeated writes would physically damage the storage chip within weeks. In TOMS, SPIFFS manages the backup log partition on the Master. Boarding records are written to SPIFFS immediately upon reception, independent of phone connectivity. If the phone disconnects mid-shift, the complete trip manifest is preserved on the Master's local storage.

### 7.2 SQLite Local Database

SQLite is a self-contained, serverless relational database engine embedded directly into the host application binary. It stores its entire database as a single file on the device's filesystem and supports full SQL query syntax, transactions, and indexing without requiring an external database server.

SQLite is relevant because the transit routes served by TOMS pass through documented cellular dead zones where mobile data connectivity is unavailable. A system that requires live internet access for every transaction would become unreliable during these segments. In the TOMS mobile application, SQLite functions as an offline-first data cache. Every boarding event is committed to the local SQLite database the instant it occurs. A synchronization flag column tracks which records have been confirmed by the cloud server, preventing both data loss and duplicate uploads.

### 7.3 Store-and-Forward Synchronization

Store-and-forward is a data transmission pattern in which records are written to local storage immediately and forwarded to a remote server only when a viable network path is detected. A background service monitors connectivity status and drains the local queue when internet access is restored, uploading pending records in chronological order.

This pattern is relevant because TOMS must guarantee a complete trip manifest regardless of cellular coverage conditions. The mobile application writes every transaction to the local SQLite database first, then monitors network availability in the background. When connectivity is detected, the sync service uploads all pending records to the central cloud database and marks each successfully transmitted row as synced, ensuring zero data loss across coverage transitions.

### 7.4 Supabase Cloud Backend

Supabase is an open-source backend-as-a-service platform built on PostgreSQL. It provides a managed relational database, auto-generated RESTful APIs, real-time subscriptions, and authentication services.

Supabase is relevant because the TOMS company dashboard requires a centralized database that multiple buses can write to concurrently and that office staff can query from any web browser without deploying proprietary server software. The Flutter app pushes boarding records to Supabase via REST API calls when connectivity is available. The company web dashboard queries the same PostgreSQL database to render live fleet occupancy rates, transaction audit logs, shift summary reports, and revenue reconciliation views.

---

## 8. Packet Integrity and Error Detection

### 8.1 CRC-16-CCITT

The Cyclic Redundancy Check with the CCITT polynomial (CRC-16-CCITT) is a 16-bit error-detection algorithm that computes a checksum over a block of data using the generator polynomial *x¹⁶ + x¹² + x⁵ + 1*. The transmitting device appends the computed checksum to the data frame; the receiving device recomputes the checksum independently and compares the values.

CRC-16-CCITT is relevant because all three TOMS communication links — NFC, ESP-NOW radio, and USB serial — are susceptible to electromagnetic interference, particularly inside a moving metal vehicle with vibrating electrical equipment. In the TOMS protocol, every packet carries a CRC-16 checksum as its final two bytes. A mismatch causes the receiving device to discard the packet rather than act on corrupted data, preventing processing of invalid fare amounts, incorrect slot assignments, or malformed alarm commands.

### 8.2 Fare Dictionary Compression

A fare dictionary is a shared lookup table that maps a compact 1-byte index to the full fare amount in centavos. Both the Master and Slave terminals load identical copies of this dictionary at boot, and the Master synchronizes updates to Slave devices via an ESP-NOW configuration message.

Fare dictionary compression is relevant because transmitting the complete boarding command structure over the narrow-bandwidth NFC-DEP link on every tap would extend the required contact duration and increase the risk of failed transfers if the conductor pulls the terminals apart too quickly. By referencing a shared dictionary, the NFC payload is reduced from a full 40-byte command to a compact 6-byte record containing only the fare index, slot number, and UTC timestamp. The Slave expands this index into the full fare locally, keeping NFC contact windows minimal.

---

## 9. Occupancy Tracking Model

### 9.1 Unscheduled Transit Operations

In Philippine public utility vehicles such as jeepneys and minibuses, passengers board at any available position rather than pre-assigned seats. Boarding and alighting points are not standardized; passengers signal the driver verbally to stop. This operational model requires an occupancy tracking system that does not depend on fixed seat assignments or structured stop schedules.

### 9.2 Dynamic Slot Assignment

The TOMS occupancy model assigns each boarding passenger a sequential slot number — #1, #2, #3, and so on — in the order they board. This number serves as the passenger's identifier on both the Slave terminal screen and the conductor's mobile dashboard. The sequence resets to #1 at the start of each trip. The occupancy rate is computed as the ratio of active Slave terminals to the configured maximum vehicle capacity.

### 9.3 Passenger State Machine

Each active passenger record transitions through three states:

- **Active (Unpaid):** The Slave terminal has been assigned and is displaying the fare. Payment has not been collected.
- **Paid:** The passenger has pressed the Slave's button and the conductor has confirmed receipt of the fare. The terminal is ready for retrieval.
- **Alarming:** The vehicle is within 200 meters of the passenger's registered destination and the fare remains unpaid. The mobile app triggers a vibration alert and alarm banner for the conductor, and sends a command to the Master to broadcast a red-screen warning to the passenger's Slave terminal.

### 9.4 Proximity Geofencing

Proximity geofencing is a location-based trigger mechanism that fires an event when the tracked vehicle crosses a defined geographic boundary around a target transit stop. The TOMS mobile application continuously compares the phone's GPS coordinates against the saved destination coordinates of every active unpaid passenger. When the computed distance falls below 200 meters, the alarm sequence is initiated. This automated alert system addresses the operational impossibility of a conductor manually monitoring every passenger's destination while simultaneously managing boarding queues and fare collection.

### 9.5 Two-Phase Optimistic Boarding

To avoid perceptible UI delay during the boarding interaction, TOMS employs a two-phase optimistic transaction model. In Phase 1 (Optimistic), the Master immediately notifies the mobile app upon detecting an NFC tap, and the app increments the occupancy count. In Phase 2 (Confirmed), the Slave transmits a full passenger boarding event to the Master via ESP-NOW after processing the boarding command locally. The Master logs this confirmation to flash storage and forwards it to the app, finalizing the transaction record. This approach ensures that the conductor's dashboard reflects new boardings instantly, while the confirmed record — including the complete fare breakdown and QR receipt data — arrives asynchronously without blocking the interface.

---

## 10. Revenue Reconciliation

Reconciliation automation is the process of cross-checking recorded digital transaction data against expected financial totals to identify revenue discrepancies. In the current operational model used by transit cooperatives, dispatchers physically visit each bus at the end of the shift to collect paper tickets and manually count the cash box — a practice that is labor-intensive, delayed, and unable to detect discrepancies until hours after the shift has ended.

In the TOMS architecture, the company web dashboard computes expected revenue for each conductor's shift directly from the recorded boarding events, applying the correct base fare and discount category (Regular, Student, PWD, or Senior) for every passenger. This calculated total is displayed alongside the conductor's declared cash collection. Any shortfall between the expected and declared amounts is flagged automatically, providing dispatchers with an immediate, objective basis for discrepancy resolution without requiring physical ticket collection or manual arithmetic.

---

## 11. Definition of Terms

| Term | Definition |
|------|-----------|
| **AES (Advanced Encryption Standard)** | A symmetric block cipher standardized by NIST in FIPS 197, used to encrypt data with key sizes of 128, 192, or 256 bits. TOMS uses AES-128 at the radio link layer and AES-256 at the application layer to protect boarding records from interception and forgery. |
| **CCMP (Counter Mode with CBC-MAC Protocol)** | An authenticated encryption protocol defined in IEEE 802.11i that combines AES-128 encryption with a message authentication code, providing both confidentiality and integrity verification for ESP-NOW radio frames. |
| **CDC-ACM (Communications Device Class – Abstract Control Model)** | A USB device class that allows an embedded microcontroller to present itself as a virtual serial port, enabling communication with Android phones without custom driver installation. |
| **CRC-16-CCITT** | A 16-bit cyclic redundancy check algorithm using the polynomial *x¹⁶ + x¹² + x⁵ + 1*, appended to every TOMS data packet to detect transmission errors caused by electromagnetic interference. |
| **eFuse (Electronic Fuse)** | One-time programmable memory cells embedded in the silicon of the ESP32-S3. They store a unique 48-bit MAC address that cannot be altered, serving as a tamper-proof hardware identity for each terminal. |
| **ESP-IDF (Espressif IoT Development Framework)** | The official C-based development framework for ESP32 microcontrollers, providing peripheral drivers, networking stacks, FreeRTOS integration, and a component management system. |
| **ESP-NOW** | A connectionless, low-overhead wireless protocol by Espressif that transmits small packets between paired ESP32 devices over the 2.4 GHz band without a router, delivering messages in under 10 milliseconds. |
| **ESP32-S3** | A dual-core, 32-bit Xtensa LX7 system-on-chip by Espressif Systems with integrated Wi-Fi, Bluetooth, native USB, and hardware cryptographic acceleration. |
| **Flutter** | An open-source UI framework by Google that compiles Dart code to native ARM machine code, featuring an asynchronous runtime that supports concurrent background tasks alongside responsive interface rendering. |
| **FreeRTOS** | An open-source real-time operating system kernel that provides priority-based preemptive task scheduling, enabling multiple concurrent firmware tasks to run with guaranteed timing priorities. |
| **Geofencing** | A location-monitoring mechanism that triggers an event when a tracked object crosses a virtual boundary around a geographic coordinate, used in TOMS to detect when unpaid passengers approach their destination stops. |
| **I2C (Inter-Integrated Circuit)** | A two-wire serial communication bus used to connect the PN532 NFC transceiver to the ESP32-S3 host microcontroller at 400 kHz. |
| **ISO/IEC 18092 (NFCIP-1)** | The international standard defining the interface and protocol rules for Near Field Communication in peer-to-peer mode, governing the Initiator-Target handshake used in TOMS NFC-DEP sessions. |
| **LVGL (Light and Versatile Graphics Library)** | An open-source embedded graphics library providing widgets, font rendering, and display-driver abstraction for microcontrollers, used to render fare screens and QR receipts on the Slave terminal's LCD. |
| **NFC (Near Field Communication)** | A short-range (under 4 cm) wireless technology operating at 13.56 MHz, used as the contactless tap interface between the Master and Slave terminals. |
| **NFC-DEP (NFC Data Exchange Protocol)** | The peer-to-peer data exchange protocol within ISO/IEC 18092 that enables bidirectional communication between an Initiator and a Target during an NFC session. |
| **NVS (Non-Volatile Storage)** | An ESP-IDF key-value store that persists configuration data and encryption keys in a dedicated flash partition across power cycles. |
| **PlatformIO** | A cross-platform embedded build system that manages toolchain configuration, dependency resolution, and project compilation for ESP-IDF firmware projects. |
| **PN532** | An NXP Semiconductors NFC controller chip supporting ISO 14443, ISO 18092, and FeliCa, capable of operating as an Initiator, Target, or card emulator. |
| **Provider** | A Flutter state management library that propagates data changes from services to UI widgets, enabling targeted rebuilds without full-screen redraws. |
| **QR Code (Quick Response Code)** | A two-dimensional matrix barcode with Reed-Solomon error correction, used by the Slave terminal to display a scannable digital receipt containing the transaction summary. |
| **RFID (Radio Frequency Identification)** | A family of wireless technologies using radio waves to identify objects via embedded tags; NFC is a short-range, high-frequency subset of RFID. |
| **SPIFFS (SPI Flash File System)** | A wear-leveled flat file system for SPI NOR flash memory, used on the Master terminal for persistent offline transaction logging. |
| **SQLite** | A self-contained, serverless relational database engine embedded in the TOMS mobile application for offline-first transaction caching with store-and-forward synchronization. |
| **ST7735S** | A Sitronix single-chip TFT LCD controller driving the Slave terminal's 1.8-inch, 128×160 pixel color display over a 4-wire SPI interface. |
| **Store-and-Forward** | A data transmission pattern where records are committed to local storage immediately and uploaded to a remote server when network connectivity becomes available. |
| **Supabase** | An open-source backend-as-a-service platform built on PostgreSQL, serving as the central cloud repository for fleet-wide occupancy and revenue data. |
| **TinyUSB** | An open-source USB device stack used by the Master firmware to expose a CDC-ACM virtual serial interface to the connected Android phone. |
