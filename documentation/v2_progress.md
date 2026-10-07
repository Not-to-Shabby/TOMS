# TOMS v2 progress log

Status of the Kotlin conductor app (`toms_android/`) against the phased plan. Decisions are in `v2_decisions.md`; deferred work is in `v2_later.md`. "Verified" means observed on the Tecno Pova 4 (model LG7n, Android 12) or in a passing test, as stated per item.

## Where each plan step stands

| Step | State | Evidence |
| --- | --- | --- |
| Phase 0 decisions | Done, MDM route still OPEN | `v2_decisions.md` |
| 1.1 Read a card | Done | 4 different cards read on the Pova 4 (9 reads). All MIFARE Classic, tech list `NfcA, MifareClassic`. |
| 1.2 Stops, fare, card state | Done | Unit tests, plus fare, "already in use" block and Paid/Return/Release seen on the phone. |
| 1.3 Local save and outbox | Done for persistence; upload not real | Restart on the phone kept 2 trips and `Pending upload: 4`. The uploader is a stub that always fails. |
| Room encryption | Done | `toms_enc.db` header is random bytes, not `SQLite format 3`. Old plaintext `toms.db` is deleted. |
| GPS logging and stop matching | Partly verified | Live fix on the phone (±3 m, 21 satellites). Nearest-stop matching and GPS-on-trip are covered by unit tests only, not seen on the phone. |
| 1.4 Backend, dashboard, receipt | Partly done | Done and tested: PostgreSQL schema and migrations, idempotent ingest, sign-in and device credentials, dashboard endpoints, web sign-in and v2 fields. Not done: receipt page, card registry and approved-cards endpoint, Docker stack (unbuilt), CI. See "Backend and web" below. |
| 1.5 Simulated Return Terminal, BLE | Not started | |
| 1.6 Uplink manager, fare settings | Not started | |
| 1.7 MDM spike, health, CI | Not started | |

Tests: Android 53 (`./gradlew test`), backend 122 (`npm test`, against a real embedded PostgreSQL 18), web 32 (`npm test`). All pass; none of this has run in CI because there is no CI yet.

## What the app does now

1. Sign in (stub), pick vehicle and route, start shift.
2. Choose boarding stop, declared destination and passenger type.
3. Tap a card. If its UID is in the approved list, a trip is created with the fare, discount, fare version, GPS fix and the declared stop. The card moves to `ASSIGNED_UNPAID`.
4. Paid, Return, Release and Clear alarm buttons move the card through the state machine. Return is a manual stand-in for the Return Terminal.
5. Every trip and every card-state change writes an event and an outbox row in one transaction. `Pending upload` shows how many are unsent.
6. Restarting the app restores trips and card states from the database.

## Code map (`toms_android/app/src/main/java/ph/toms/conductor/`)

| Path | Purpose |
| --- | --- |
| `domain/Config.kt` | `Stop`, `FareConfig`, `DiscountCategory`, `ApprovedCards` (UID to `card_uuid`), `TomsConfig`, `haversineMeters` |
| `domain/FareCalculator.kt` | Fare from distance, discount percent and rounding step, all from config. Money is in centavos. |
| `domain/CardStateMachine.kt` | Card states and transitions, plus `TapDebouncer` |
| `domain/TripFactory.kt` | Builds a trip: card lookup, state check, stops, override with reason, GPS fix. Also `ReceiptLinks`. |
| `domain/StopMatcher.kt` | Nearest stop within 300 m. Rejects fixes older than 30 s or with accuracy worse than 300 m. |
| `nfc/NfcReader.kt` | NFC reader mode, ISO 14443-A only |
| `location/LocationTracker.kt` | GNSS fixes from `LocationManager` (no Google Play services). Updates at 2 s / 5 m. |
| `data/db/` | Room entities and DAOs, `DatabaseKeyProvider` (Keystore-wrapped SQLCipher key) |
| `data/TripRepository.kt` | Writes trip, card, event and outbox row in one transaction |
| `data/TestConfig.kt` | Seeded placeholder stops, fares and the four test cards |
| `sync/Sync.kt`, `sync/UploadWorker.kt` | `OutboxFlusher` (marks only acked IDs delivered), stub uploader, 15-minute WorkManager job |
| `ui/` | Login, assignment, tap screen and `SessionViewModel` |

Database tables: `trips`, `cards`, `events` (append-only, local sequence number), `outbox`, `config_cache`. `config_cache` exists but nothing reads or writes it yet.

## Build and run

- Needs JDK 17+ and the Android SDK (path in `toms_android/local.properties`, which is git-ignored).
- The first build needs network access: the Kotlin Compose plugin is not in the offline cache.
- `./gradlew :app:assembleDebug`, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- On the phone, NFC must be on and location permission granted (the app asks; `adb shell pm grant ph.toms.conductor android.permission.ACCESS_FINE_LOCATION` also works).
- The sign-in is a stub: any non-blank username and password work. Real authentication arrives in 1.4.

## Backend and web (Phase 1.4, partly done)

Work is on branch `v2/android-phase1`; `main` is untouched. `toms_backend/` was hardened in place.

| Area | What exists |
| --- | --- |
| Database | PostgreSQL with ordered migrations (`migrations/001`-`004`), applied in a transaction under an advisory lock. SQLite is gone. |
| Events | `events` table keyed on the phone's `event_id` (uuid, unique). Holds card, trip, fare, fare version, override and the GPS fix (latitude, longitude, accuracy, fix time; all-or-none enforced). The original payload is kept as jsonb. `trip_status` view gives the latest card state and whether the trip was paid. |
| Ingest | `POST /api/events` answers with `accepted`, `duplicates` and `rejected` lists. A reused `event_id` with different content is rejected, not acknowledged. One bad row does not sink the batch. Events from a phone with a wrong clock are stored and flagged. |
| Sign-in | argon2id passwords, HS256 JWTs (issuer, audience, pinned algorithm), 5 failed tries per username block it for 15 minutes (in memory). No seeded accounts: create the first admin with `ADMIN_PASSWORD=... npm run create-admin -- <name>`. |
| Phones | Credential `<device_id>.<secret>`, issued once at `POST /api/devices`, stored only as a hash, revocable. A batch naming another device is refused. |
| Dashboard API | Fleet status, audit logs, revenue, CSV export, conductor list, delivery summary. Admin only. "Today" uses `REPORT_TIMEZONE` (default Asia/Manila). |
| Live socket | Admin only. A phone upload pushes `fleet_update` to open dashboards. |
| Web | Sign-in page, one authenticated API client, token in `sessionStorage`, audit and analytics pages on the v2 fields, authenticated CSV download. |

Two bugs worth knowing about, both found only by running the real thing: the live socket could never connect because Express was registered after socket.io (fixed, `src/server.ts`), and a smoke script left test databases running when it failed.

### Not done in 1.4

- **Receipt page and the per-trip redirect.** No endpoint turns a `card_uuid` into a receipt yet.
- **Card registry.** There is no table mapping NFC UID to `card_uuid`, and no endpoint serving the approved-cards list the app caches. The Android app still uses the seeded list in `TestConfig.kt`.
- **Android upload.** The app's uploader is still a stub. Nothing connects the phone to this server yet, so the end-to-end "tap to dashboard" check has not been done on a real phone. The upload request shape was tested with payloads copied from the Android code, not with the app itself.
- **Backlog on the dashboard.** The server cannot see a phone's unsent events. That needs a heartbeat from the app. The delivery summary shows what has arrived, by channel and delay.
- **Docker Compose and the Dockerfile** were rewritten but never built: Docker is not installed on this machine.
- **CI** (GitHub Actions) is not set up.
- **Web pages not visually checked.** Nothing in the web app has been opened in a browser. Fleet Manager, Conductors and Route Builder were only moved to the shared client and have no tests. The map reads the new GPS fields but was not seen. Route Builder still calls the OSRM demo server over plain http.
- **Occupancy** on the dashboard is now "cards currently out", not a seat map. The old seat-map view has no data behind it.
- **Fare settings** are still the old `base_fare` and `per_km_fare` columns on routes. Versioned fares and discount settings are Phase 1.6.

### Security notes

- The old SQLite files (`toms_backend.db`, with a `test1` conductor whose password is `password123`, hashed with unsalted SHA-256, plus about 2,000 seeded events) were removed from git, but **they are still in public history on `origin/main`**. Treat that login as known to anyone. It no longer exists in the new system, but if `password123` or the `asd` account was ever reused anywhere, change it. Removing the file from history means rewriting `main`, which has not been done and needs your decision.
- `.env` files are ignored. `.env.example` has placeholders and the server refuses to start with a missing, short or placeholder `JWT_SECRET`.
- The login lockout is per username and in memory: a restart clears it, and it does not stop one attacker trying many usernames.
- The web token sits in `sessionStorage`, readable by any script on the page, so keep third-party scripts off the dashboard.

## Differences from the old Flutter app

See the comparison in the conversation history; the points that matter for later work:

- Kotlin reads cards on the phone. Flutter relied on an ESP32 Master over USB serial and has no phone-side NFC.
- The old `ceil` per km and flat 20% discount are not used. They survive only in tests named `LEGACY_OLD_APP`.
- GPS differs from `gps_service.dart`: updates every 5 m instead of 20 m, and no match is returned beyond 300 m, where Flutter always names the nearest stop. The per-stop `radiusM` was not ported (Flutter uses it only in the proximity alarm).
- Only Haversine was ported directly. The stop model, GPS service and nearest-stop logic were written fresh.

## Known gaps and unverified items

- **Fares are not verified.** Base fare 15.00, 4 km, 2.50 per km, 20% discounts and whole-peso rounding are placeholders in `TestConfig.kt`. They must be checked against the government source and cited for Chapter IV 4.9.
- **Stops are placeholders.** The seeded coordinates are not near real stops, so nearest-stop matching shows "no stop nearby" on the device.
- **Upload is a stub.** The offline-then-sync test cannot run until the backend exists.
- **WorkManager job** is scheduled but has not been seen running on the phone.
- **Cards are MIFARE Classic.** They read reliably, but the design lists NTAG21x, DESFire or NTAG 424 DNA. Classic cannot support authenticated cards. None of the four UIDs starts with `04` (the NXP manufacturer byte; they start `6F`, `36`, `56`, `14`), which suggests clone cards. This was not investigated.
- **Key loss:** the database key is bound to the phone's Keystore, so unsynced data cannot be recovered after a phone swap or reset.
- **Session is not remembered.** Sign-in and shift choice must be repeated after a restart.
- **GPS battery cost is unmeasured.**
