# TOMS v2 decisions (Phase 0)

See also `v2_progress.md` (what is built and verified) and `v2_later.md` (deferred work).

Status: living record. Source design: "TOMS v2 design and phased plan". Items marked OPEN are not decided.

## Decided

| Topic | Decision |
| --- | --- |
| Conductor app | New native Kotlin / Jetpack Compose app in `toms_android/` with Hilt, Room and WorkManager. `toms_mobile` (Flutter) is read-only reference, then retired. |
| HTTP client | Retrofit + OkHttp with the kotlinx.serialization converter. Ktor is not used. |
| Backend | Express + TypeScript + PostgreSQL. SQLite is dropped. |
| Card identity | The NFC UID and the printed QR `card_uuid` are different identifiers. The server registry maps `nfc_uid` to `card_uuid`; the app caches the approved-cards list (UID to `card_uuid`) as part of its config. Receipt links are built from `card_uuid` plus a per-trip token, never from the UID. An unknown UID is rejected. |
| Fare timing | The fare is fixed at boarding from the declared destination. The declared destination is a suggestion and is not enforced. Each trip records the declared stop, the actual stop if different, the fare version used, and any conductor override with a reason. |
| Fare parameters | Base fare, base distance, per-km rate, rounding and discount categories come from server settings (Phase 1.6), not constants. |
| Deferred | RTOS choice (Zephyr vs ESP-IDF) and the SMS gateway do not block Phase 1. |

## Legacy behavior (old Flutter app, not a spec)

`toms_mobile/lib/services/session_service.dart` uses: base fare 15.00 up to 4 km, then 2.50 per started km (`ceil`), a flat 20% discount for student, PWD and senior, and rounding to the nearest whole peso. The Kotlin port reproduces this once in tests labelled `LEGACY_OLD_APP` to confirm the port matches the old app. These are not asserted to be correct fares. Correct fares come from the server settings and the government source recorded in Chapter IV 4.9, which must still be verified and cited.

## OPEN: MDM route

Headwind's kiosk mode is an Enterprise feature, so the Community server does not provide it. Options:

- A: TOMS implements lock-task kiosk itself (the design doc's recommendation).
- B: Headwind is used only for enrollment and silent updates, and kiosk is added another way.

Only one DPC can be device owner, so Headwind's agent and a TOMS-owned kiosk may conflict. The Phase 1.7 spike tests Headwind Community enrollment and silent update only, and TOMS lock-task is built and tested separately on an adb-provisioned device. Record the outcome here and close this item.

## Phase 2 note

`shared_lib/pn532` is an ESP-IDF I2C driver. It is reusable as is only if ESP-IDF is chosen. Under Zephyr it needs a port, as do the protocol and crypto helpers.
