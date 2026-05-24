# Transportation Occupancy Monitoring System (TOMS)

TOMS is a secure, offline-first, contactless transit ticketing and manifest system designed to modernize public utility vehicle operations (such as minibuses and modern jeepneys in Iligan City, Philippines). It replaces traditional manual ticket issuance and cash collection with a robust Master-Slave-Phone network architecture utilizing contactless NFC-DEP Peer-to-Peer communication, ESP-NOW wireless synchronization, and USB CDC-ACM mobile integration.

---

## 🚀 System Architecture

TOMS is composed of three primary tiers:
1. **Slave Terminal (Tap / Interface Device)**
   * **Hardware:** Built on the ultra-compact **ESP32-S3 Super Mini** development board.
   * **Power Management:** Onboard TP4054 battery charging IC management with built-in resistor voltage divider (ratio 0.5) connected to GPIO 1 (ADC1_CH0) to monitor LiPo cell status.
   * **Peripherals:** PN532 NFC transceiver (configured in Target mode for NFC-DEP P2P at 424 kbps), ST7735S 128×160 TFT LCD (using LVGL 8.3 UI), and a tactile boarding trigger button.
   * **Functionality:** Passive interface that displays transaction UI, accepts button/tap inputs, generates QR receipts, and exchanges fast data frames with the Master.
2. **Master Handheld Terminal (Business Logic & Storage)**
   * **Hardware:** ESP32-S3 microcontroller.
   * **Functionality:** Conducts cryptographic operations, holds fare tables, acts as the transaction broker, writes records to an offline-first wear-leveled SPIFFS partition, and pipes live transactions to the operator's smartphone via USB CDC-ACM.
3. **Mobile Dashboard App (Cloud Gateway)**
   * **Framework:** Native Android App built with Flutter.
   * **Functionality:** Acts as the operator's primary screen, displays real-time boarding analytics, caches transactions in a local SQLite database, and pushes buffered records to a Supabase backend when an active internet connection is detected.

---

## 🛠️ Hardware Design Documentation

The physical prototypes were developed using Fritzing. Below are the designs (breadboard layouts, schematics, and PCB diagrams) for both the Master and Slave terminals.

### 1. Master Handheld Terminal

| View Type | Hardware Illustration |
|---|---|
| **Breadboard Layout** | ![Master Breadboard](hardware/master/breadboard/Master%20Design%20V1_bb.png) |
| **Schematic** | ![Master Schematic](hardware/master/schematic/Master%20Design%20V1_schem.png) |
| **PCB Layout** | ![Master PCB](hardware/master/pcb/Master%20Design%20V1_pcb.png) |

### 2. Slave Terminal (ESP32-S3 Super Mini)

| View Type | Hardware Illustration |
|---|---|
| **Breadboard Layout** | ![Slave Breadboard](hardware/slave/breadboard/Slave%20Design%20v2.1_bb.png) |
| **Schematic** | ![Slave Schematic](hardware/slave/schematic/Slave%20Design%20v2.1_schem.png) |
| **PCB Layout** | ![Slave PCB](hardware/slave/pcb/Slave%20Design%20v2.1_pcb.png) |

---

## 📡 Protocols & Security

* **Communication:** Standardized packet structure (`protocol.h`) with a 2-byte synchronization header (`0xAA55`), 1-byte length, 1-byte message type, 1-byte sequence number, variable payload, and a 2-byte CRC-16-CCITT checksum.
* **Optimistic NFC-DEP Transactions:** Master acts as the Initiator (`InJumpForDEP`) and Slave as the Target (`TgInitAsTarget`). A dictionary-based compression reduces boarding commands to 6 bytes to minimize RF communication window latency.
* **Authentication:** Device eFuse MAC validation combined with link-layer ESP-NOW CCMP (AES-128) security.
