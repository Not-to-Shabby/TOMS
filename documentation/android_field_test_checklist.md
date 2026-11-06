# Android field test checklist (Tecno Pova 4)

For the researcher to run. **Nothing below has been run.** Tick the box only after observing the expected result, and write the date and any surprise in Notes. Use a debug build pointed at a test server, not production data.

Setup: phone enrolled with a device token, a shift open, at least one approved test card, `adb` connected. Dashboard open on a laptop to watch events arrive.

| # | Test | Steps | Expected | Done | Notes |
| --- | --- | --- | --- | --- | --- |
| 1 | Upload job runs on its own | Create two trips, put the app in the background, leave the phone for 15 to 20 minutes on mobile data. Run `adb shell dumpsys jobscheduler \| grep -A5 ph.toms.conductor`. | The `outbox-upload` periodic job is listed; after its window the pending count on More falls to 0 and the events show on the dashboard. | [ ] | |
| 2 | Airplane mode 10 minutes | Turn airplane mode on. Tap 3 cards, mark one paid. Wait 10 minutes. Turn it off. | While offline the pending count rises and the taps still work. After reconnecting (allow the stability delay of the uplink manager) the count drains to 0. The dashboard shows each trip **once**. | [ ] | |
| 3 | Mobile data only | Turn Wi-Fi off. Tap a card. | The event reaches the dashboard within about 30 seconds. | [ ] | |
| 4 | Wi-Fi to mobile switch | Start on Wi-Fi, tap a card, switch Wi-Fi off during the next tap. | No lost or duplicate trips on the dashboard. | [ ] | |
| 5 | App killed mid-shift | With 2 open trips, swipe the app away. Reopen. | Shift, open trips and pending events are still there. (The signed-in conductor is restored only if the session branch is merged.) | [ ] | |
| 6 | Phone restart mid-shift | Restart the phone with a shift open and events pending. Open the app. | Same as 5; pending events upload once the network is up. | [ ] | |
| 7 | Return Machine out of range and back | Connect to the Return Machine, walk out of range for 2 minutes, return. | Link state shows disconnected, then reconnects without a restart; a card tapped at the RM while connected gets a fare verdict. | [ ] | |
| 8 | Low battery | Run the phone below 15% and then 5% with the shift open (battery saver on). | Taps still work; note whether GPS, BLE and uploads continue. | [ ] | |
| 9 | GPS lost in a building | Go indoors with no sky view and tap a card. | The trip is recorded without GPS, and the app does not freeze. After going outside a fix returns. | [ ] | |
| 10 | Long shift battery | Full charge, shift open with the screen on for 4 hours, note the battery percent every hour. | Record the drain per hour. (Input for the update-distance decision.) | [ ] | |

## Reading results

- `adb shell dumpsys jobscheduler` lists jobs for the package; look for the job and its last run time.
- `adb logcat -s WM-WorkerWrapper` shows worker runs.
- A duplicate on the dashboard is a failure of test 2, 4 or 6; record the event ids.
