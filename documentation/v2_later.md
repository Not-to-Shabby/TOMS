# TOMS v2 Later list

Work that is deliberately not being done now. Each item says where it came from, so it is clear which ones the design doc defers and which ones I added.

## From the design doc (section 10a, status "Later")

| Item | Notes |
| --- | --- |
| Dual-SIM switching and encrypted SMS fallback | Extension. State in the paper that it is not evaluated unless tested. Dual-SIM data switching is device-dependent. |
| Dispute resolution | Old Flutter feature (`dispute_resolution_screen.dart`, QR scan). Not an evaluation criterion. |
| Shift history and JSON export | Old Flutter features (`shift_history_screen.dart`, share as JSON). Built now (PR #24, 2026-11-14): the history reads the frozen summaries, and the export carries the shift header, the summary and the trip lines. |
| Proximity alarm | Old Flutter geofence alarm (`proximity_service.dart`, `proximity_alarm_banner.dart`). Parked. |

## GPS refinements (added after comparing with `gps_service.dart`)

| Item | Why it is deferred |
| --- | --- |
| Per-stop radius (`radiusM`) | Flutter defaults to 100 m and uses it only for the proximity alarm. Build it together with the alarm. Needs a tie-break for overlapping radii. |
| Heading or direction check | Stops on opposite sides of a road are close but different. A radius cannot separate them. |
| Update-distance tuning (5 m now, Flutter used 20 m) | Decide from the 1.7 battery measurements, not by guessing. |
| Stale fix while stopped | A bus waiting at a stop gets no new fix, and after 30 s the fix is dropped from trips. Consider a one-shot fresh fix at tap time or a longer allowance when stationary. Worth doing before field tests. |
| Radius for "nearest stop" | The matcher uses each stop's own radius (default 100 m, `DEFAULT_STOP_RADIUS_METERS`, same as the server). There is no 300 m cutoff any more. Check the radii against real routes. |

## Other items added during the build

| Item | Notes |
| --- | --- |
| Remember the signed-in conductor and shift | Today they are lost on restart. Comes with real auth in 1.4. |
| Read and write `config_cache` | Table exists, nothing uses it yet. Needed for fetched config in 1.6. |
| Authenticated cards | Current test cards are MIFARE Classic, which cannot do this. Decide on NTAG 424 DNA or DESFire. See card cloning in the design doc risks. |
| Investigate the unusual card UIDs | `6F`, `36`, `56`, `14` first bytes. Likely clone cards. |
| Data recovery after phone swap | The Keystore-bound key cannot be moved. Rely on the outbox reaching the server. |
| Verify the WorkManager upload job on the phone | Scheduled for every 15 minutes, never observed. |
| Replace `TestConfig.kt` with real stops and verified fares | Blocks any meaningful field test. |
| Record a late payment after a write-off (`fare_recovered`) | Deferred from the shift-tools build (2026-11-14), after being coded and then removed. Release is final is a confirmed business rule in `v2_decisions.md`, and once an unpaid fare is written off and the card is released, a passenger who pays later has no trace in the app. Two things hold it back: the server counts any `fare_recovered` as collected revenue (migration 011's `paid` flag covers it), so a mistaken or unauthorized record would inflate revenue with no way to tell it from a real one; and who may record it is undecided — an office role, or anyone but the shift's own conductor. When that is decided the phone work is small: a reason-prompted audit event on a written-off trip, mirroring the write-off payload, touching no card state and no frozen summary. |
| Show write-offs on the web dashboard | `trip_status` reads only card-state events, so a written-off trip shows as unpaid, then as available once released. A small backend view change would show it as written off. |
| Reconcile lost cards (`RECONCILE_LOST`) | The state machine has it; the phone can mark a card lost (PR #24) but nothing frees a lost card back into circulation yet. An office-side action, so it waits. |
| Shift: verified identity | The conductor name on shifts and write-offs is the stub sign-in name until real sign-in exists. |
| Shift: remember the open shift in the interface after a restart | The shift survives in the database and is resumed or ended by the same rules, but the screen goes back to sign-in. Comes with real sign-in. |
| Peak occupancy and seat numbers | The Flutter summary showed them; the Kotlin app has no seat or capacity concept. |
