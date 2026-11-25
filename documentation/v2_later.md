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
| Return Machine layout 2 on `main` and on hardware | The phone now asks for wire layout 2 (outcome byte, `dropped_reads`, `late_replies`) and records exact `rm_unverified` / `rm_lost_gap` / `rm_late_replies`, but section 7 is only on the firmware draft PR #31 and nothing here has run against a real machine. Until a machine answers `07 02`, the phone keeps the older inferences. Open: confirm on hardware that `dropped_reads` and `late_replies` read as the spec says, and that a machine reboot reads as a counter reset the phone ignores. |
| Return Machine: signed writes, pairing notices and key replace on hardware | Built against `firmware/PROTOCOL.md` section 8 of the hardening draft PR #35 (not merged). Needs, on a real machine with that firmware: signed writes accepted, an unsigned and a wrongly signed `05` refused, pairing refused outside the window, a replace with the reply lost, and the Android pairing variant on the Tecno phone. The bond can now be cleared at the machine (section 8.7: hold the button through the 5 s of the pairing window; the signing key is kept), so a second phone can be enrolled and a cleared phone can pair again; the conductor app recovers from the stale pairing that leaves behind (prompt and "Forget old pairing", see `rm_protocol_phone.md`). Still untested on hardware: that Android's `removeBond` is reachable on the Tecno phone (it is a system API, not in the public SDK, so the app falls back to telling the conductor to use Bluetooth settings), and that a machine with a cleared bond really ends the link the way the spec says. The phone cannot choose the pairing method, so it reports a weak one instead of preventing it. |
| Return Machine: key rotation and machine reset on firmware without `08` | The firmware stores the key once (`keystore_provision` returns `-EEXIST`, no diag command erases or replaces it), so rotating a key or recovering a reset machine needs its key storage cleared by hand (re-flash or a settings-partition wipe). The phone and dashboard flag the resulting mismatch plainly. A firmware command that replaces the key only when the new one arrives signed by the old one (or after a physical button hold) would close this. |
| Return Machine keys: master key rotation and backup | `RM_KEY_MASTER_KEY` seals every machine key; changing it makes every sealed key unreadable (the routes answer 500 for those machines). A re-seal command (open under the old master key, seal under the new one) is not built. The master key must be backed up with the other server secrets; deploy documentation has the variable name, and `deploy/` files were not changed here. |

| Record a late payment after a write-off (`fare_recovered`) | Deferred from the shift-tools build (2026-11-14), after being coded and then removed. Release is final is a confirmed business rule in `v2_decisions.md`, and once an unpaid fare is written off and the card is released, a passenger who pays later has no trace in the app. Two things hold it back: the server counts any `fare_recovered` as collected revenue (migration 011's `paid` flag covers it), so a mistaken or unauthorized record would inflate revenue with no way to tell it from a real one; and who may record it is undecided — an office role, or anyone but the shift's own conductor. When that is decided the phone work is small: a reason-prompted audit event on a written-off trip, mirroring the write-off payload, touching no card state and no frozen summary. |
| Work profile: intent-based setup, remove-profile protection, kiosk | `ph.toms.conductor` now runs as the owner of its own work profile (`documentation/work_profile_setup.md`). Not built: starting `ACTION_PROVISION_MANAGED_PROFILE` from inside the app (the adb steps do the same job), blocking the user from removing the work profile (the source read does not show a profile owner able to), and any kiosk or lock-task mode (the platform refuses lock task inside a managed profile; it needs a device owner on a fully managed phone). Everything on the real Tecno Pova 4 is untested; the unticked checklist is in that document. |
| Show write-offs on the web dashboard | `trip_status` reads only card-state events, so a written-off trip shows as unpaid, then as available once released. A small backend view change would show it as written off. |
| Reconcile lost cards (`RECONCILE_LOST`) | The state machine has it; the phone can mark a card lost (PR #24) but nothing frees a lost card back into circulation yet. An office-side action, so it waits. |
| Shift: verified identity | The conductor name on shifts and write-offs is the stub sign-in name until real sign-in exists. |
| Shift: remember the open shift in the interface after a restart | The shift survives in the database and is resumed or ended by the same rules, but the screen goes back to sign-in. Comes with real sign-in. |
| Peak occupancy and seat numbers | The Flutter summary showed them; the Kotlin app has no seat or capacity concept. |
