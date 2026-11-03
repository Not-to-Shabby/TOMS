TITLE: Drain the outbox as soon as a trip event is committed, so receipts are visible mid-trip

BASE: main

Fixes the "No active trip found for this card right now." dead end that followed the receipt-privacy gate (PR #13):

### Background & Problem
The server now only links a card's static QR to a receipt while that card's latest trip is open (PR #13).
But the conductor app's only automatic drain of its offline outbox is a 15-minute periodic WorkManager job
(Android's minimum period), plus auto-drain when connectivity is restored after a dead zone, plus the manual
Upload now button. Writing a trip event never triggered an upload, so in a normal ride the boarding and
payment events sat on the phone and reached the server minutes later — often in the same batch as the return.
The server therefore never observed an "open trip" moment, and every scan of the card's QR answered
"No active trip found for this card right now." even though the gate itself worked as designed.

### Solution
Every event append now requests an immediate drain:
1. **`UploadKick` interface (new, `sync/UploadKick.kt`):** a one-method request for an outbox drain that
   tolerates being called for every event. `WorkManagerUploadKick` enqueues a unique one-time
   `UploadWorker` (`ExistingWorkPolicy.KEEP`, so concurrent kicks coalesce into one drain), with the same
   CONNECTED constraint and backoff as the periodic job. It survives the app being killed and waits for
   connectivity offline; the 15-minute periodic drain stays as the backstop.
2. **Kick after commit, not during the transaction** (`TripRepository`): `recordTrip`, `recordCardState`,
   `startShift` (only a genuinely started shift), `endShift` (only when something was closed) and
   `writeOffAndRelease` (only when the write-off proceeded) kick after `db.withTransaction` returns, so the
   drain can never read an outbox that does not yet hold the event — the mistake that would leave a boarding
   invisible until the next periodic run. No-op writes (resumed shift, refused write-off, missing shift) do
   not kick.
3. **Wiring:** `DataModule` binds `WorkManagerUploadKick` and injects it into the `TripRepository` provider.
   The shared connectivity constraint was extracted to `UploadWorker.NETWORK_CONSTRAINTS`.
4. **Server untouched:** the strict receipt gate from PR #13 stays exactly as it is — no grace period, no
   privacy weakening. The passenger scanning mid-ride now gets their receipt within seconds of boarding.

### Tests Run
- New `WorkManagerUploadKickTest` (Robolectric + WorkManager test init): repeated kicks coalesce into one
  pending drain; the kicked drain requires connectivity like the periodic one. Added
  `androidx.work:work-testing:2.10.0` as a test dependency.
- New kick tests in `ShiftRepositoryTest` (the TripRepository harness): event writes kick an immediate drain
  while no-op writes (resumed shift, refused write-off, missing shift) do not; ending a shift and a real
  write-off kick once each.
- Full suite: 364 debug / 354 release unit tests, 0 failures.
