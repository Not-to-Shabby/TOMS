# TOMS v2 progress log

Status of the Kotlin conductor app (`toms_android/`) against the phased plan. Decisions are in `v2_decisions.md`; deferred work is in `v2_later.md`. "Verified" means observed on the Tecno Pova 4 (model LG7n, Android 12) or in a passing test, as stated per item.

## Where each plan step stands

| Step | State | Evidence |
| --- | --- | --- |
| Phase 0 decisions | Done, MDM route still OPEN | `v2_decisions.md` |
| 1.1 Read a card | Done | 4 different cards read on the Pova 4 (9 reads). All MIFARE Classic, tech list `NfcA, MifareClassic`. |
| 1.2 Stops, fare, card state | Done | Unit tests, plus group tallies, fare preview, in-use card lookup and Paid/Return seen on the phone. |
| 1.3 Local save and outbox | Done | Room schema 2 with tested migration; restart keeps pending events; SQLCipher database encryption verified on phone. |
| Room encryption | Done | `toms_enc.db` header is random bytes, not `SQLite format 3`. Old plaintext `toms.db` is deleted. |
| GPS logging and stop matching | Partly verified | Live fix on the phone (±3 m, 21 satellites). Nearest-stop matching and GPS-on-trip covered by tests. Tap-time fresh fix for a wait at a stop covered by tests, not yet observed on the phone (see `android_field_test_checklist.md`). |
| Shift summary and End Shift | Done | Room schema 3 with tested migration; shift rules, write-off and release in the domain, repository and view model; summary screen, End Shift prompt and shift ended confirmation checked on the Tecno Pova 4 (a fresh shift with nothing in it; a shift with trips and unpaid cards was exercised in tests only). Backend accepts the new events unchanged. Rules in `v2_decisions.md`. |
| 1.4 Backend, dashboard, receipt | Done | Complete end-to-end: PostgreSQL schema and migrations (001–006), group trips, idempotent ingest, argon2 auth, device credentials, dashboard endpoints, web sign-in, Retrofit Android uploader, card registry with Web NFC, dynamic approved cards sync, static QR redirect, passenger receipt page, verified fare matrix with LTFRB document upload, and GitHub Actions CI. |
| 1.5 Return Machine BLE client | Merged (PR #11) and exercised on the real Return Machine, checklist not run | `ble/` (client, protocol, fake link for tests); byte-level tests in `RmProtocolTest`. The fare-verdict round trip (phone answers the Return Machine's `paid_check`) and the remote alarm clear were exercised against the real machine on the Pova 4 after the CCCD-write fix (bd7edb7). The scenarios in `android_field_test_checklist.md` (out of range and back, low battery) are not yet run. Protocol sync with the firmware branch (PR #28): the phone now verifies the signed notifies against the per-machine key, sends the read ack, de-duplicates reads by (machine id, read_seq) in Room schema 6, and records rm_stored_read / rm_unverified / rm_lost_gap machine-health events that upload like any event and are counted in the backend's rm_health view (`documentation/rm_protocol_phone.md`). Trailer vectors were produced from the firmware's own signing code on a host; nothing here is verified against real hardware. The key now comes from the server (Return Machines page on the dashboard, `GET /api/device/return-machine-key`, AES-256-GCM at rest): the phone fetches it, writes it to the machine with diag 06 and activates it on the first verified message; see "Where the key comes from" in `rm_protocol_phone.md`. The firmware's key is write-once, so a rotation or machine reset ends in a flagged "key mismatch", not a silent drop. |
| 1.6 Uplink manager, fare settings | Merged (PR #12, #14); enrolled and seen uploading, checklist not run | `sync/UplinkManager.kt` with hysteresis and auto-drain; upload kick; fares fetched from the server config. A phone was enrolled with a device token against the live server and its uploads were seen end-to-end; the airplane-mode/reconnect and Wi-Fi-switch scenarios in `android_field_test_checklist.md` are not yet run. |
| Receipt privacy and fares on the web | Merged (PR #13, #18) | Static QR redirects only while a trip is active; receipts stop after the card is returned; a recovered fare shows paid on the receipt. Deployed on the OVH VPS at toms.perchbuilds.me (`deploy/verify.sh` 12/12). |
| Real conductor sign-in and session | Merged (PR #19), verified on the phone | Server-checked login, encrypted stored token, restore of the open shift on restart, sign-out. |
| Background running during a shift | Merged (PR #20), installed but not field-tested | Foreground service (`location\|connectedDevice`), battery-optimization prompt asked once, keep-screen-on setting. |
| Health telemetry (`device_status`) | Merged (PR #22 backend, #23 phone) | Heartbeat at shift start, every 5 minutes, and at shift end: outbox state, battery, GPS age, network, Return Machine link. Strict server ingest, `device_status` view, research queries. Not yet observed in a real shift. |
| Shift tools | Merged (PR #24), verified in tests only | Shift history from frozen summaries with JSON export (card UID note: it leaves the phone through the share sheet); lost-card button with a confirm step. Recording a late payment was deferred — see `v2_later.md`. |
| 1.7 MDM spike, health, CI | Partly | Health telemetry is built (see above). MDM/kiosk not started; `v2_decisions.md` still lists the MDM route as open. |

Tests: Android 401 debug unit tests, 0 failures (2026-11-14, branch `v2/android-gps-docs`); backend 320 and web 44 as of PR #22 (2026-11-09). CI pipeline configured in `.github/workflows/ci.yml` runs all three suites on every push.

## What the app does now

1. **Sign in (real), vehicle & route assignment:** The server checks the password; the token is kept encrypted and the open shift is restored on restart. Shift starts with vehicle plate and route header.
2. **Board Screen:**
   - Destination picker ("To") and boarding stop ("From") with one-tap stop switcher or GPS nearest stop.
   - Huge live fare total (72sp) updating immediately with each passenger tap.
   - Passenger buttons (Regular, Student, PWD, Senior) with immediate count feedback. Child is treated as Student.
   - One card covers a family/group: fare is computed and rounded per person and summed.
   - One full-width Clear button. After a card tap starts a trip, the group resets to one regular and the destination stays.
   - Tapping an available card starts the trip.
   - Tapping an already assigned card opens its existing trip and payment state instead of an error.
3. **Collect Screen:**
   - Unpaid trips and alarms listed first, oldest first.
   - Quick "Paid" and "Calc" buttons.
   - Card tap on Collect tab focuses and highlights that passenger's trip.
   - Returning a paid card frees the card back to available immediately.
4. **Calc Screen:**
   - Change mode: displays trip fare due, quick bill buttons (₱20, ₱50, ₱100, ₱200, ₱500, ₱1000) or custom keypad amount, and exact change to give back (whole-centavo math).
   - General calculator mode: arithmetic calculator with left-to-right operations, centavo rounding, and overflow safeguards.
5. **More Screen (Settings & Diagnostics):**
   - Shift summary and End Shift, reached from a button at the top.
   - Left / Right hand preference (mirrors the keypad corners and tab order).
   - Server Sync: view device ID, configure Server URL, paste device enrollment token, and manual "Upload now" button with real-time status.
   - Diagnostics: waiting events count, fare version, approved cards count, GPS fix and accuracy, recent card reads log.
6. **Haptics & Audio:** Haptic waveform vibration + audio ToneGenerator cues (Done = single buzz/ack, Refused = double buzz/nack, Opened = buzz/double beep). Color is never the sole feedback.
7. **Retrofit Uploader:** Batches outbox events into `POST /api/events` with Bearer device token; acknowledges both accepted and duplicate event IDs. A tap-time fresh GPS fix rescues the location when the vehicle has been waiting at a stop (update distance unchanged, pending battery measurements).
8. **Background running:** Foreground service for the shift's duration, one-time battery-optimization prompt, keep-screen-on setting.
9. **Return Machine link:** Automatic scanning and reconnection, payment verdicts written back to the terminal, remote alarm clear. A 5-minute health heartbeat reports outbox, battery, GPS age, network and link state to the server.
10. **Shift tools:** Shift history from the frozen summaries with a JSON export through the share sheet, and a lost-card button with a confirm step (a lost card is not a write-off).

## Code map (`toms_android/app/src/main/java/ph/toms/conductor/`)

| Path | Purpose |
| --- | --- |
| `domain/Config.kt` | `Stop`, `FareConfig`, `DiscountCategory`, `ApprovedCards`, `TomsConfig`, `haversineMeters` |
| `domain/FareCalculator.kt` | Per-person fare from distance, discount and rounding step. Money in centavos. |
| `domain/Passengers.kt` | `PassengerLine`, `GroupFare` (per-person rounding, group sum), `PassengerTally` |
| `domain/CardStateMachine.kt` | Card states and transitions, plus `TapDebouncer` |
| `domain/TapResolver.kt` | Distinguishes starting new trip vs opening existing trip vs unknown/lost/alarm cards |
| `domain/TripFactory.kt` | Builds group trip record, conductor override, GPS fix, receipt links |
| `domain/BoardFlow.kt` | Board tap handling, preview pricing, CollectList sorting and totals |
| `domain/Calculator.kt` | Change calculator, quick bills, Keypad (7-digit limit), GeneralCalc |
| `feedback/Feedback.kt` | `DeviceFeedback` (VibrationEffect waveforms + ToneGenerator), `RecordingFeedback` |
| `settings/` | `Handedness`, `PrefsSettings`, `DeviceConfigStore`, `PrefsDeviceConfig` |
| `location/LocationTracker.kt` | Platform `LocationManager` GNSS (no Google Play services) |
| `data/db/` | Room entities (including shifts), DAOs, `MIGRATION_1_2` and `MIGRATION_2_3`, `DatabaseKeyProvider` (Keystore SQLCipher) |
| `domain/ShiftSummary.kt` | `Shift`, the recorded trip facts, and `ShiftSummary.of`: collected cash, own-trip reconciliation, carried-in lines, per-type breakdown |
| `data/TripRepository.kt` | Single-transaction trip, card state, event and outbox writing |
| `sync/RetrofitEventUploader.kt` | Retrofit 2.11 + OkHttp 4.12 uploader with kotlinx.serialization |
| `sync/Sync.kt`, `UploadWorker.kt` | `OutboxFlusher`, periodic WorkManager job |
| `ui/theme/` | `TomsTheme`, `TomsColors` (4.5:1+ WCAG sunlight contrast), `TouchTarget` (64dp), `BigButton`, `StatusChip` |
| `ui/` | `ConductorApp` (StatusStrip, TabBar), `BoardScreen`, `CollectCalcScreens`, `MoreScreen`, `ShiftScreens` (summary, End Shift prompt, shift ended), `ConductorViewModel` |

## Backend and web (Phase 1.4)

Merged to `main` and deployed to the OVH VPS (toms.perchbuilds.me).

| Area | What exists |
| --- | --- |
| Database | PostgreSQL with ordered migrations (`001`–`012`, the last one the telemetry `device_status` view), advisory lock. |
| Events | `events` table with unique `event_id`, GPS fields, `passenger_count`, `passengers` jsonb, `receipt_token` uuid, `trip_lines` and `trip_status` views. |
| Ingest | Idempotent `POST /api/events` with accepted/duplicates/rejected lists. Validates passenger lines against count and fares. |
| Auth | argon2id passwords, HS256 JWTs, login limiter (15 min lock after 5 failures). Device credentials `<device_id>.<secret>` via `POST /api/devices`. |
| Dashboard API | Fleet status (occupancy by people), audit logs, revenue analytics (per-type breakdown), CSV export (with passenger count and formula guards), conductor list, delivery summary. |
| Live socket | Admin-only socket.io connection with real-time `fleet_update` and `new_event` emissions on upload. |
| Card Registry | Database `cards` table with uppercase hex `nfc_uid` and static `card_uuid`. Web NFC scanning (`window.NDEFReader`) on Chrome for Android + manual fallback. Printable SVG QR card stickers linking to `/r/:card_uuid`. Dynamic `GET /api/cards/approved` endpoint cached by Android app in Room. |
| Verified Fares | Database `fare_matrices` table with route, base fare/distance, per-km rates, statutory discounts, and `order_reference`. Multipart upload of official scanned LTFRB photocopy document (JPEG/PNG/PDF up to 10MB) with static serving at `/uploads/fares/`. |
| Public Receipts | Static card QR route `/r/:card_uuid` dynamically resolves latest trip and redirects to `/receipt/:trip_token`. Public mobile-friendly digital receipt displaying trip details, fare breakdown, and expandable official LTFRB signed document viewer. Free of PII. |
| CI Pipeline | Automated GitHub Actions workflow (`.github/workflows/ci.yml`) running test suites and builds across Android, backend (with PostgreSQL service), and web on every push and pull request. |
| Web | Sign-in page, `sessionStorage` token management, audit log with party detail, revenue split by category, authenticated CSV download, Card Registry, and Fare Settings. |

### Completed in 1.4

All requirements of Phase 1.4 are now implemented and tested:
- End-to-end trip creation from card tap on phone to live dashboard receipt.
- Idempotent event ingest with zero duplicate records.
- Offline-first queuing surviving app restarts and network cut-offs, followed by clean synchronization.
- Card registry with Web NFC and static QR receipt redirection.
- Verified fare settings with official LTFRB document upload and verification on passenger e-receipt.
- Automated CI pipeline.
