TITLE: Fix receipt privacy: gate static card QR by active trip status

BASE: main

Prevents passenger trip details from leaking when reusable physical cards are handed to new passengers or returned at the Return Terminal:

### Background & Problem
The physical passenger card has a permanent printed QR code pointing to `https://toms.perchbuilds.me/r/<card_uuid>`. When scanned by a passenger's camera, the server redirected to the latest trip's unique URL (`/receipt/<receipt_token>`).
However, because it previously selected `ORDER BY effective_at DESC LIMIT 1` unconditionally, even after a passenger alighted and returned their card (state: `RETURNED`), anyone scanning the physical card's QR code (or a photo of it) was still redirected to the previous rider's personal receipt and travel history until a new ride began.

### Solution (Zero changes to physical printed cards)
The printed QR on the card (`/r/<card_uuid>`) remains 100% static and identical. The server now checks whether the card is in an active open trip before redirecting:
1. **Active Trip Gating:** `/r/:card_uuid` queries `trip_status` and verifies that the latest trip is currently active (`current_state IN ('ASSIGNED_UNPAID', 'ASSIGNED_PAID') AND effective_at > now() - interval '24 hours'`).
2. **Returned / Inactive Cards:** Once a card is marked `RETURNED` (or `RETURNED_UNPAID`), scanning `/r/:card_uuid` redirects to `/receipt/inactive?card=<uuid>` (or answers JSON 404), showing: *"This passenger card is in circulation, but has no active trip recorded right now."* No past receipt details are leaked.
3. **Permanent Receipt Access for Legitimate Passengers:** The rider who scanned the QR on the bus and opened their unique digital receipt (`/receipt/<receipt_token>`) retains permanent access to their digital receipt even after returning the card.

### Tests Run
- All 310 backend tests pass (`toms_backend/test/receipt.test.ts` added 2 new tests verifying that returned cards refuse to leak receipts, while direct receipt token lookups continue to function).
- All 313 web tests pass (`toms_web`).
- Clean TypeScript checks on backend and web (`npx tsc --noEmit` and `npx tsc -b`).
