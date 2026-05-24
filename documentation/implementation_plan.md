# Phase 3: Flutter Mobile App Implementation Plan

This plan outlines the architecture and implementation steps for the TOMS Flutter mobile application (`toms_mobile`). This app acts as the bridge between the physical hardware (ESP32 Master) and the cloud backend (Supabase), prioritizing an offline-first architecture.

## Overview

The Flutter app will run on an Android device connected to the Master ESP32 via USB OTG. It will listen for JSON messages over a virtual serial port (CDC-ACM), cache transaction data locally, and synchronize with Supabase whenever internet connectivity is available.

## User Review Required

> [!IMPORTANT]
> **Flutter Installation:** Ensure you have the Flutter SDK installed and available in your system PATH. 
> **Supabase Project:** You mentioned previously that you don't have the Supabase project set up yet. We will need the `SUPABASE_URL` and `SUPABASE_ANON_KEY` during development. We can use placeholder values for now and update them later in Phase 4.

## Open Questions

> [!WARNING]  
> 1. Do you have a preferred state management solution? (e.g., `Provider`, `Riverpod`, `Bloc`). If no preference, I will default to `Provider` for simplicity and effectiveness.
> 2. For the local database, `Isar` is highly performant and great for offline-first apps, but `sqflite` is more traditional. I plan to use **Isar** unless you prefer otherwise.
> 3. Should I go ahead and run `flutter create toms_mobile` in the `d:\TOMS\` directory as the first step?

## Proposed Architecture

### Dependencies
- **Communication:** `usb_serial` (for USB CDC-ACM communication with the Master ESP32)
- **Local Database:** `isar` & `isar_flutter_libs` (NoSQL, extremely fast, perfect for caching logs)
- **Cloud Backend:** `supabase_flutter` (Database sync, authentication)
- **State Management:** `provider`
- **UI & Aesthetics:** `google_fonts`, `lucide_icons` (for premium, modern UI)

### Core Services
1. **`UsbService`:** Manages the USB connection lifecycle, reads JSON lines from the ESP32, and broadcasts events.
2. **`DatabaseService`:** Wraps Isar for local storage of routes, fare tables, and passenger transaction logs.
3. **`SyncService`:** Monitors network connectivity and orchestrates bidirectional sync with Supabase.

### Application Flow
1. **On Boot:** Initialize Isar DB, load Supabase config, and attempt to connect to any attached USB device.
2. **On USB Message:** Parse `{"evt": "passenger", ...}`. Save to local Isar DB instantly.
3. **On Network Available:** `SyncService` queries Isar for unsynced logs and pushes them to Supabase in batches.

## Implementation Steps

### 1. Project Initialization
- Run `flutter create --platforms android toms_mobile` in `d:\TOMS\`.
- Add required dependencies in `pubspec.yaml`.
- Set up Android `AndroidManifest.xml` with USB host permissions (`android.hardware.usb.host`).

### 2. Local Database (Isar) Setup
- Define Isar collections: `PassengerLog` (transactions) and `FareConfig` (routes/prices).
- Implement CRUD operations.

### 3. USB Serial Integration
- Implement USB device discovery and connection handling.
- Build a line-based stream parser to handle the JSON protocol from the Master ESP32.
- Implement the handshake sequence to get Master status (battery, pending logs).

### 4. Supabase Integration
- Initialize Supabase client.
- Create synchronization logic to push `PassengerLog` records to the cloud and pull down `FareConfig` updates.

### 5. Premium UI Design
- Develop a stunning, dark-mode native dashboard.
- Display real-time connection status, battery level of the Master device, and sync queues.
- Implement micro-animations for USB connection events and sync operations.

## Verification Plan

### Automated/Local Tests
- Run the Flutter app in debug mode on an Android device.
- Use `adb logcat` to verify USB permissions and CDC-ACM data reception.

### Manual Verification
- Connect the Android phone to the Master ESP32 via a USB OTG cable.
- Verify that the app detects the device, completes the handshake, and accurately displays the Master's battery percentage and status.
- Trigger a dummy transaction (or press the button on the Slave) to verify the data flows all the way to the Flutter UI.
