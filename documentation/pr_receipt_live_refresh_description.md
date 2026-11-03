TITLE: Receipt page keeps up with the trip: UNPAID turns to PAID without a reload

BASE: main

Fixes the report that a receipt left open during the ride never updates from UNPAID to PAID.

### Problem

`ReceiptView` fetched `/api/receipt/:token` exactly once on mount. A passenger who scanned while boarding and kept the tab open watched a frozen receipt: the conductor marking the fare paid (or overriding the destination) never appeared until a manual refresh.

### Solution

While the shown trip is still open (`card_state` is `ASSIGNED_UNPAID` or `ASSIGNED_PAID`), the page re-reads the receipt every 5 seconds and updates in place. The backend already serves live state (the `trip_status` view is derived from events), so no server change is needed.

- **Stops when final:** once the card is back (`RETURNED`, `RETURNED_UNPAID`, `AVAILABLE`), the receipt is final and polling stops — no idle battery drain on a forgotten tab.
- **Background tabs:** phone browsers pause timers, so an extra read also runs on `visibilitychange`/`focus` when the passenger returns to the tab.
- **Resilient:** a failed refresh keeps the last good receipt on screen and tries again next tick. A mid-trip scan that resolves via the card link keeps working (the poller attaches to the token URL after the #16 navigation).
- **Rate limits:** 12 requests/minute per open tab against the public limiter's 120/minute — a busload of passengers on one carrier address stays well inside it.
- Sockets were deliberately not used: the existing socket.io stream is admin-only (fleet positions), and a public per-token socket room would add auth surface for little gain over a 5-second poll.

### Tests Run
- Web: 319 tests pass, `npx tsc -b` clean. New tests with fake timers: an open receipt refreshes and UNPAID becomes PAID without a reload; a returned receipt is final and no further requests are made.
