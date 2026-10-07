# TOMS v2 Later list

Work that is deliberately not being done now. Each item says where it came from, so it is clear which ones the design doc defers and which ones I added.

## From the design doc (section 10a, status "Later")

| Item | Notes |
| --- | --- |
| Dual-SIM switching and encrypted SMS fallback | Extension. State in the paper that it is not evaluated unless tested. Dual-SIM data switching is device-dependent. |
| Dispute resolution | Old Flutter feature (`dispute_resolution_screen.dart`, QR scan). Not an evaluation criterion. |
| Shift summary and history | Old Flutter feature (`shift_summary_screen.dart` totals revenue, passengers and discounts). Use as the layout reference. |
| Proximity alarm | Old Flutter geofence alarm (`proximity_service.dart`, `proximity_alarm_banner.dart`). Parked. |

## GPS refinements (added after comparing with `gps_service.dart`)

| Item | Why it is deferred |
| --- | --- |
| Per-stop radius (`radiusM`) | Flutter defaults to 100 m and uses it only for the proximity alarm. Build it together with the alarm. Needs a tie-break for overlapping radii. |
| Heading or direction check | Stops on opposite sides of a road are close but different. A radius cannot separate them. |
| Update-distance tuning (5 m now, Flutter used 20 m) | Decide from the 1.7 battery measurements, not by guessing. |
| Stale fix while stopped | A bus waiting at a stop gets no new fix, and after 30 s the fix is dropped from trips. Consider a one-shot fresh fix at tap time or a longer allowance when stationary. Worth doing before field tests. |
| Cutoff of 300 m for "nearest stop" | Arbitrary until real routes are measured. |

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
