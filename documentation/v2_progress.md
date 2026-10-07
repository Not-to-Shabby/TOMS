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
| 1.4 Backend, dashboard, receipt | Not started | |
| 1.5 Simulated Return Terminal, BLE | Not started | |
| 1.6 Uplink manager, fare settings | Not started | |
| 1.7 MDM spike, health, CI | Not started | |

Unit tests: 53, 0 failures (`./gradlew :app:testDebugUnitTest`).

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
