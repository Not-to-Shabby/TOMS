# TOMS Mobile Application Documentation

This document describes the application design, architecture, database configurations, APIs, user interface, program flow, and testing procedures for the TOMS (Transportation Occupancy Monitoring System) Flutter Mobile Application. It has been updated to reflect the completion of **Phase 4a**.

## System Architecture

The TOMS Mobile Application is a Flutter-based mobile app designed to run on the conductor's Android device. It acts as the orchestration bridge between the physical vehicle hardware (Master ESP32-S3), the passenger, and the cloud database:

1. Establishes a local physical link with the Master controller using an Android USB OTG connection, operating over a virtual serial CDC-ACM interface.
2. Serves as the real-time operator interface, showing vehicle occupancy, active routes, current passenger records, and GPS-aware proximity alarms.
3. Provides an offline-first transaction database, caching passenger boarding logs locally when mobile signals are unavailable.
4. Orchestrates synchronisation queues to push cached passenger records to the company REST API, using secure JWT-based authentication.

```mermaid
graph TD
    subgraph Conductor Mobile
        UI[Flutter Home Shell]
        AppState[AppState Provider]
        Sqlite[(SQLite Local Cache)]
        UsbService[UsbService CDC-ACM]
        SyncService[SyncService]
        AuthService[AuthService]
        GpsService[Gps & Proximity]
    end
    
    subgraph Vehicle Hardware
        Master[ESP32-S3 Master]
        Slave[Slave Terminals]
    end
    
    subgraph Cloud
        Backend[(Company Server / Cloud Database)]
    end
    
    UsbService -- USB OTG / JSON-Line -- Master
    Master -- ESP-NOW / P2P -- Slave
    UsbService --> AppState
    AppState --> UI
    AppState --> Sqlite
    GpsService --> AppState
    AuthService --> SyncService
    AppState --> SyncService
    SyncService -- HTTPS REST (JWT Auth) -- Backend
```

## Application Architecture

The mobile application utilizes a clean Model-View-Service architecture built around the Provider state-management framework to achieve separation of concerns:

* **View Layer (Material 3)**:
  * `HomeShell`: A Swipeable PageView bridging the primary workflows.
  * `BoardingScreen`: Full-screen UI for generating passenger tickets and managing the pending queue.
  * `DashboardScreen`: Real-time Occupancy Panel, active passenger slots, and diagnostic console.
  * `ShiftHistoryScreen`: Grouped daily transaction logs with JSON export capabilities.
* **Service Layer**: Decoupled singletons managing background hardware and data tasks:
  * `UsbService`: Manages USB serial lifecycles, parses incoming JSON frames.
  * `AuthService`: Manages JWT tokens in secure storage and vehicle assignment states.
  * `DatabaseService`: Manages SQLite operations, schema upgrades, and local query execution.
  * `SessionService`: Manages boarding queues, active passenger sessions, base fare calculations, and discount models.
  * `OccupancyService`: Computes dynamic slot allocations and publishes real-time occupancy rates based on vehicle capacity.
  * `GpsService & ProximityService`: Tracks the bus's physical location against mapped `TransitStops` and triggers local alarms when nearing a passenger's destination.
  * `ConnectivityService`: Monitors network status changes using the connectivity plugin.
  * `SyncService`: Stores events locally and flushes the synchronization queue automatically upon reconnection, providing progress callbacks to the UI.
* **State Orchestrator (`AppState`)**: Directs the flow between hardware events, UI state, and database transactions.

## Offline-First & Shift Lifecycles

The app implements a strict offline-first design:
* **Auth & Assignment**: The app is gated behind `LoginScreen` and `VehicleAssignmentScreen`. A shift does not begin until the conductor selects a vehicle, locking in the `maxCapacity`.
* **Local Caching**: Incoming USB passenger events (NFC taps, button presses) are evaluated against the pending queue, serialised into local SQLite tables, and immediately confirmed locally.
* **Store-and-Forward Sync**: The `SyncService` operates asynchronously. It batches unsynced records from the SQLite database. If the device is offline, it waits. Upon reconnection, it flushes the queue to the backend. A `_SyncStatusBar` provides UI feedback.
* **End of Shift**: Conductors invoke `AppState.endShift()` to clear active sessions and log out of the vehicle. If the backend is unavailable, the conductor can manually extract their shift logs as a JSON file via Bluetooth or Email using `share_plus`.

## Hardware Pin Assignment & Connectivity

Because the mobile application is a software layer running on commercial Android hardware, it does not directly manage low-level GPIO pin configurations. Instead, it accesses hardware interfaces through:
* **Interface**: USB Controller on the Android SoC.
* **Protocol Mode**: Configured in USB Host Mode via the Android SDK (`android.hardware.usb.host` feature).
* **Physical Connection**: Android USB Type-C interface connected to the Master ESP32-S3 via a USB OTG cable.

## Required Libraries

The following dependencies are declared inside `pubspec.yaml`:

1. `usb_serial`: Wraps Android USB host APIs to discover, open, and communicate with USB-to-serial chips without device root.
2. `provider`: State-management container used to inject services.
3. `sqflite`: SQLite database plugin for local caching.
4. `flutter_secure_storage`: Encrypts the company JWT token and securely stores the conductor's credentials.
5. `geolocator`: Accesses phone GPS for coordinate tracking.
6. `vibration` & `flutter_local_notifications`: Sounds haptic and visual alarms for unpaid passengers approaching their stops.
7. `share_plus`: Exports shift JSON summaries for offline terminal reconciliation.
8. `connectivity_plus`: Listens to network connectivity events to trigger automatic API syncing.
9. `lucide_icons` & `google_fonts`: Tailwind UI / modern typography.

## Protocol Used in Hardware Abstraction Layer

The application-level HAL protocol uses newline-delimited JSON objects transmitted over the USB virtual serial port at 115200 baud:

* **Conductor Phone to Master Commands**:
  * Handshake: `{"cmd":"handshake"}`
  * Get Status: `{"cmd":"get_status"}`
  * Board Passenger: `{"cmd":"board", "uid":"...", "fare":1300, "seat":1, "route":1, "vehicle_id":"..."}`
* **Master to Conductor Phone Events**:
  * Acknowledgment: `{"evt":"ack", ...}`
  * Status Diagnostics: `{"evt":"status", ...}`
  * NFC Card Tap: `{"evt":"nfc_tap", "uid":"A1B2C3D4E5F6", "seat":0}`
  * Button Boarding Request: `{"evt":"button_press", "mac":"A1:B2:C3:D4:E5:F6"}`
  * Passenger Alighted: `{"evt":"release", "mac":"A1:B2:C3:D4:E5:F6"}`
  * Error Warning: `{"evt":"error", "msg":...}`

## Program Flow Table

| Step | State/Action | Condition / Input | Output / State Transition |
|---|---|---|---|
| 1 | Boot Initialization | App launch | Instantiates SQLite services, registers providers, validates JWT token. Routes to `LoginScreen` or `VehicleAssignmentScreen`. |
| 2 | Shift Start | Vehicle Selected | Sets `maxCapacity`, routes to `HomeShell`, triggers automatic USB scanning loop. |
| 3 | Boarding Prep | Conductor interacts | Conductor selects destination/type. GPS auto-suggests nearest boarding stop. Passenger added to `SessionService.pendingQueue`. |
| 4 | USB Device Connected | Master USB plugged in | Opens serial port, 115200 baud, transmits `handshake`. Updates Dashboard status. |
| 5 | Passenger Boarding Request | NFC Tap OR Button Press | Fires `nfc_tap` / `button_press` over USB. App checks if a queued ticket exists, assigns the ticket, sends `board` command, and pushes a `board` event to `SyncService`. |
| 6 | Payment Trigger | Conductor collects fare | Conductor collects cash. Marks slot as PAID. Clears proximity alarms. Pushes a `payment` event to sync queue. |
| 7 | Proximity Alarm | GPS approaches destination | `ProximityService` calculates distance < 200m. If unpaid, triggers `Vibration` and `ProximityAlarmBanner`. |
| 8 | Passenger Alighting | Slave Long Press (3s) | Slave sends release notification. Phone receives `release` event, frees the slot, and syncs release. |
| 9 | Cloud Sync Queue | Event added to sync queue | If connectivity is detected, POSTs to backend using JWT Auth. Updates UI sync badge on success. |

## Security Architecture

1. **Network Security**: Connects to cloud endpoints over HTTPS (TLS encryption) to prevent MITM attacks.
2. **API Authentication**: The `SyncService` dynamically requests a bearer token from `AuthService` (`Authorization: Bearer <token>`). Tokens are securely encrypted on disk.
3. **Data Protection**: Local transactions are segregated by shift. Debug logs are routed through Flutter's `debugPrint`, preventing leakage to Android logcat in release builds.

## Planned Features (Upcoming Phases)

1. **Route Builder Syncing**: Dynamic injection of `TransitStop` coordinates from the backend API replacing the static `stops.json`.
2. **Master USB Configuration Push**: Pushing updated fare dictionaries and vehicle capacities dynamically to the Master's NVS memory upon shift start.
