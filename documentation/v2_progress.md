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
| GPS logging and stop matching | Partly verified | Live fix on the phone (±3 m, 21 satellites). Nearest-stop matching and GPS-on-trip covered by tests. |
| 1.4 Backend, dashboard, receipt | In progress | Done and tested: PostgreSQL schema and migrations (001–005), group trips, idempotent ingest, argon2 auth and device credentials, dashboard endpoints, web sign-in, Retrofit Android uploader. Not done: receipt page, card registry, Docker stack (unbuilt), CI. See "Backend and web" below. |
| 1.5 Simulated Return Terminal, BLE | Not started | |
| 1.6 Uplink manager, fare settings | Not started | |
| 1.7 MDM spike, health, CI | Not started | |

Tests: Android 154 (`./gradlew test`), backend 138 (`npm test`, against a real embedded PostgreSQL 18), web 37 (`npm test`), web build clean.

## What the app does now

1. **Sign in (stub), vehicle & route assignment:** Starts shift with vehicle plate and route header.
2. **Board Screen:**
   - Destination picker ("To") and boarding stop ("From") with one-tap stop switcher or GPS nearest stop.
   - Huge live fare total (72sp) updating immediately with each passenger tap.
   - Passenger buttons (Regular, Student, PWD, Senior) with immediate count feedback. Child is treated as Student.
   - One card covers a family/group: fare is computed and rounded per person and summed.
   - Undo and Clear buttons (swappable left/right based on hand preference).
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
   - Left / Right hand preference (mirrors Undo/Clear and tab order).
   - Server Sync: view device ID, configure Server URL, paste device enrollment token, and manual "Upload now" button with real-time status.
   - Diagnostics: waiting events count, fare version, approved cards count, GPS fix and accuracy, recent card reads log.
6. **Haptics & Audio:** Haptic waveform vibration + audio ToneGenerator cues (Done = single buzz/ack, Refused = double buzz/nack, Opened = buzz/double beep). Color is never the sole feedback.
7. **Retrofit Uploader:** Batches outbox events into `POST /api/events` with Bearer device token; acknowledges both accepted and duplicate event IDs.

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
| `data/db/` | Room entities, DAOs, `MIGRATION_1_2`, `DatabaseKeyProvider` (Keystore SQLCipher) |
| `data/TripRepository.kt` | Single-transaction trip, card state, event and outbox writing |
| `sync/RetrofitEventUploader.kt` | Retrofit 2.11 + OkHttp 4.12 uploader with kotlinx.serialization |
| `sync/Sync.kt`, `UploadWorker.kt` | `OutboxFlusher`, periodic WorkManager job |
| `ui/theme/` | `TomsTheme`, `TomsColors` (4.5:1+ WCAG sunlight contrast), `TouchTarget` (64dp), `BigButton`, `StatusChip` |
| `ui/` | `ConductorApp` (StatusStrip, TabBar), `BoardScreen`, `CollectCalcScreens`, `MoreScreen`, `ConductorViewModel` |

## Backend and web (Phase 1.4)

Work is on branch `v2/android-phase1`.

| Area | What exists |
| --- | --- |
| Database | PostgreSQL with ordered migrations (`001`–`005`), advisory lock. |
| Events | `events` table with unique `event_id`, GPS fields, `passenger_count`, `passengers` jsonb, `trip_lines` and `trip_status` views. |
| Ingest | Idempotent `POST /api/events` with accepted/duplicates/rejected lists. Validates passenger lines against count and fares. |
| Auth | argon2id passwords, HS256 JWTs, login limiter (15 min lock after 5 failures). Device credentials `<device_id>.<secret>` via `POST /api/devices`. |
| Dashboard API | Fleet status (occupancy by people), audit logs, revenue analytics (per-type breakdown), CSV export (with passenger count and formula guards), conductor list, delivery summary. |
| Live socket | Admin-only socket.io connection with real-time `fleet_update` emissions on upload. |
| Web | Sign-in page, `sessionStorage` token management, audit log with party detail, revenue split by category, authenticated CSV download. Fake battery display removed. |

### Not done in 1.4

- **Receipt page and the per-trip redirect:** Turning a `card_uuid` into a public receipt URL.
- **Card registry endpoint:** Database table mapping NFC UID to `card_uuid` and serving approved-cards list to phone.
- **Docker Compose:** Configured but unbuilt (Docker not installed on host).
- **CI:** GitHub Actions workflow.
