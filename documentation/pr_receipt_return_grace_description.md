TITLE: Receipt return grace window: a passenger who scans as they get off still gets their receipt

BASE: main

Follow-up to the receipt privacy gate (PR #13) and the outbox upload kick (PR #14), closing the one honest gap they left: a passenger who walks off without scanning mid-ride could never recover their receipt.

### Behavior

Scanning the card's printed QR (`/r/:card_uuid`) now resolves in this order:

1. **Latest trip open** (`ASSIGNED_UNPAID`/`ASSIGNED_PAID` within 24h) → that trip's receipt, exactly as before. **This is what protects the next passenger**: the query always takes the card's latest trip, so once the next rider's boarding reaches the server, their scans show *their own* receipt — never the previous passenger's, even inside the grace window.
2. **Latest trip handed back within `RETURN_GRACE_MINUTES` (30)** → the last receipt, with a `?grace=1` hint and `"grace": true` in the JSON response.
3. **Otherwise** (returned longer ago, abandoned, or no trips) → the existing inactive notice. Lost/retired/suspended cards still 410 before any of this.

### The confusion guard (review note)

The flagged risk — "after the card is returned, the next user scans the same QR and sees the previous receipt" — is structurally bounded two ways:
- **Newest trip wins:** the grace lookup only fires when the card's latest trip is *not* open. A new boarding replaces the resolution within seconds (the upload kick delivers taps in ~2s).
- **Visible banner:** while a grace receipt is shown, the web receipt page displays "The card has already been returned — this is the receipt from its most recent trip." so nobody mistakes it for their own. A plain mid-trip scan shows no banner.

### Changes

- `toms_backend/src/dashboard.ts`: `RETURNED_STATES` (`RETURNED`, `RETURNED_UNPAID`, `AVAILABLE` — the card-out-of-passenger's-hands states) and `RETURN_GRACE_MINUTES = 30` exported next to `OPEN_STATES`.
- `toms_backend/src/receipt.ts`: when the latest trip is not active, one extra indexed lookup asks whether any return-state event for the card is inside the grace window; if so, redirect to `/receipt/<token>?grace=1` (HTML) or answer JSON with `grace: true`. The shared inactive answer moved to a small `inactiveScan` helper.
- `toms_web/src/pages/ReceiptView.tsx`: shows the grace banner when landed with `?grace=1` (server 302 path) or when the `/api/r` resolve answers `grace: true` (SPA card-link path).
- `toms_backend/test/receipt.test.ts`: the returned-card leak test now backdates its return beyond the grace window (its intent is unchanged); two new tests — within-grace scan opens the last receipt flagged `grace`, and a newer boarding beats the grace window so the next passenger gets their own receipt.
- `toms_web/src/pages/ReceiptView.test.tsx`: three new tests — banner via URL hint, banner via resolve flag, and no banner for a plain mid-trip scan.

### Tests Run
- Backend: 312 tests pass, `npx tsc --noEmit` clean.
- Web: 316 tests pass, `npx tsc -b` clean.
