# Pairing a phone with a one-time code

**Status.** The server side is built (migration 017, `toms_backend/src/pairing.ts`, `toms_backend/test/pairing.test.ts`), and so is the dashboard "Phones" page (`toms_web/src/pages/Phones.tsx`, section 6). The app's "Scan to enrol" screen is **not built**: section 7 is the contract it has to meet, and until it exists a code issued on the dashboard cannot be used from a phone (the page says so). Nothing here has run on a phone.

## 1. Why

Enrolling a phone used to mean reading its Device ID out to an administrator, who ran `setup-pilot.mjs enrol-device` (or `POST /api/devices`) and carried the printed token back to the phone. The token passed through people, and a Device ID read out wrong enrolled a different ID: the token then named a phone that does not exist, and the real phone's events were refused (the server checks that each event's `device_id` matches the one in the token).

With a pairing code the phone enrols itself:

1. An administrator picks a vehicle on the dashboard and gets a code (and a QR of it). No token is shown to anyone.
2. The phone sends the code and its **own** Device ID to `POST /api/devices/pair`.
3. The server creates the device on the code's vehicle and returns the token in that response, once. The app stores it the way it stores a token today.

`POST /api/devices` (the administrator enrols a Device ID and is shown the token) is unchanged and still works; `setup-pilot.mjs enrol-device` keeps using it.

## 2. The code

Typed as three groups of four, for example `K7M2-9XQF-R4TH`. Any case, with or without dashes or spaces, is accepted.

| Part | Length | Stored | Why it exists |
| --- | --- | --- | --- |
| id | 4 symbols (30⁴ = 810,000) | as is | Public. It finds the row, so a wrong guess can be counted against **that** code. Without it a wrong guess names no code and a code could never lock. |
| secret | 8 symbols (30⁸ ≈ 6.6 × 10¹¹, 39.3 bits) | **never**; only HMAC-SHA256 over `id:secret`, keyed with a key derived from `JWT_SECRET` | The part that has to be guessed. |

The alphabet is `23456789ABCDEFGHJKMNPQRSTVWXYZ`: 30 symbols without the look-alikes `0 O 1 I L U`. A test tries all 36 letters and digits as the first symbol of an id against the database's own check and confirms it accepts exactly the 30 of this alphabet (the check is one pattern for all four positions).

The secret is short, so a plain hash of it could be brute-forced from a copy of the table in minutes. The key stays outside the database, so a copy of the table alone cannot be attacked offline. Someone who also holds the server's `JWT_SECRET` could, but a code lives ten minutes. The same fact means changing `JWT_SECRET` makes every code still open stop matching (the right secret then counts as a wrong try); they expire within ten minutes, so issue a fresh one.

## 3. Limits

| Limit | Value | What happens |
| --- | --- | --- |
| Lifetime | 10 minutes | The code stops working. |
| Wrong secrets per code | 5 | The code **locks for good**. Right secret or not, it is refused afterwards. |
| Open codes at once | 10 | A new code is refused with 409 until one is cancelled, used, locked or expired. The count is exact when administrators issue at the same moment (an advisory lock). |
| Pair attempts per address | 10 a minute | 429 with `Retry-After`. Every request to the pair route counts, valid or not. |
| Use | once | The first phone to pair wins; a code never enrols two phones, including when two phones try in the same instant. |

The per-address counter is in memory (a server restart clears it) and keyed by the client address as the server sees it (`TRUST_PROXY_HOPS`, 2 in production).

### What the numbers mean

These are computed from the constants, not measured:

- A stranger who has named an open code's id gets 5 tries at the secret: 5 / 30⁸ ≈ 7.6 × 10⁻¹² per code.
- Naming an open id blind: with 10 open codes, each request has a 10 / 810,000 ≈ 1.2 × 10⁻⁵ chance, so about 81,000 requests are expected. At the per-address limit that is about 5.6 days from **one** address. The limit is per address, so many addresses go faster.
- What finding an id buys a stranger is the ability to burn that code's 5 tries and **lock** it. That is a denial of service on enrolment (the administrator issues another code), not a way to enrol.
- The 403 answer is the same for a wrong secret, an unknown id, and a used, cancelled, locked or expired code. **Response time is not equalised:** a wrong secret on a live code does a database write the other cases do not, so a careful attacker could tell an open id from an unknown one by timing. The id is the public half and learning it does not help with the secret.
- Anyone who can see the QR or the code on the administrator's screen within the ten minutes can enrol a phone of their own before the real one does. The dashboard has to show who used each code (section 6).

## 4. Routes

All administrator routes need an administrator login: no token gives 401, a conductor token 403, and a phone token 401.

| Route | Who | Notes |
| --- | --- | --- |
| `POST /api/pairing-codes` | admin | Body `{vehicle_id, label?}`. 201 `{id, code, code_id, vehicle_id, label, created_by, created_at, expires_at, expires_in_seconds, qr_payload}` with `Cache-Control: no-store`. **The only time the code is visible.** 400 for an unknown vehicle or a bad body, 409 when 10 codes are open. |
| `GET /api/pairing-codes` | admin | Newest 50: `id, code_id, vehicle_id, label, created_by, created_at, expires_at, attempts, used_at, used_device_id, status`. `status` is `used`, `cancelled`, `locked`, `expired` or `open` (first match wins). No secret, no hash, no way to read a code back. |
| `DELETE /api/pairing-codes/:id` | admin | 200 `{status: 'cancelled'}`; 404 if unknown, used or already cancelled. |
| `GET /api/pairing-audit` | admin | Newest 100 of `pairing_code_issued`, `pairing_code_cancelled`, `pairing_code_locked`, `device_paired`, with `code_id`, `vehicle_id`, `device_id`, `actor` (`admin:<name>`, `device:<id>` or `system`). |
| `GET /api/devices` | admin | `device_id, vehicle_id, label, enrolled_at, revoked_at, revoked, last_seen`. No token and no hash. |
| `DELETE /api/devices/:deviceId` | admin | Unchanged: revokes. |
| `POST /api/devices/pair` | **public** | See below. |

`POST /api/devices/pair` takes `{code, device_id}` and **nothing else**: a body that names a vehicle, or any other field, is a 400 and uses nothing up. The vehicle always comes from the code.

| Answer | When |
| --- | --- |
| 201 `{device_id, token, vehicle_id}` | The code is good. `Cache-Control: no-store`. |
| 400 | The request is malformed, or the code is not 12 symbols from the alphabet, or the Device ID is not 3–64 of letters, digits, `-`, `_`. Not counted as a wrong try on any code. |
| 403 `That pairing code did not work…` | Wrong secret, unknown id, or a used, cancelled, locked or expired code, or a deleted vehicle. One answer for all. |
| 409 `This phone is already enrolled, or its ID was revoked…` | Only reachable with a **good** code. The code is not used up. |
| 429 | Too many attempts from this address; see `Retry-After`. |

`qr_payload` is `toms-enrol://v1?server=<url-encoded address>&code=<code>`, or `null` when the server has no `PUBLIC_BASE_URL`. The address comes from that server setting, never from the request, so the Host header cannot change it (a test sends a hostile one). It is not a web link on purpose: a scanner app that opens it cannot send the code to a web server's log.

## 5. What is stored, and what never is

- `pairing_codes`: the public id, the keyed hash of the secret, the vehicle, an optional label, who issued it, times, the count of wrong tries, and how it ended. Never the secret or the whole code.
- `device_pairing_audit`: who did what to which public id and vehicle. A test checks that no secret, whole code, stored hash or token appears in the audit rows, in the three list routes, or in anything written to the console, across issue, wrong tries, lock, pair and cancel.
- A phone's token is stored the way it always was: only the hash.

## 6. The dashboard step (built: Dashboard > Phones)

The rules below are what the page does, and each is covered by a test in `toms_web/src/pages/Phones.test.tsx`.

- The code and the QR are shown **once**, with a countdown that runs from this computer's clock (`expires_in_seconds` from the answer, not the server's `expires_at`), and they leave the screen when the countdown reaches zero, when a phone uses the code, when it locks or is cancelled, or when the page is left. The code is never put in browser storage or in the address, and the list shows only the public id (`K7M2-····-····`).
- The page reads the lists every 3 seconds while a code is on screen, so the moment a phone uses the code it says which one (**Paired: phone … is now enrolled on …**) and tells the administrator to check it is the phone they meant. A tab that was in the background looks at the clock again as soon as it is shown.
- When `qr_payload` is `null` only the typed code is shown, and the page says there is no QR.
- Each code's `status` is shown. For a `used` code the page shows `used_device_id`, the phone's name, and a revoke button.
- **Revoke needs a confirmation step.** Both revoke buttons (the phone list and the codes list) open a dialog that names the phone, its vehicle and its last upload, and says it cannot be undone and that the Device ID can never be enrolled again; nothing is sent until its own **Revoke phone** button is pressed, it cannot be pressed twice while the request is out, and Cancel, Escape or a click outside send nothing. The route itself is unchanged and has no confirmation of its own.
- **Not available until the app step exists:** the page carries a note saying the conductor app has no enrolment screen yet and naming the command that works today (`setup-pilot.mjs … enrol-device`), and the code panel says "The app cannot use this code yet". Both come from one flag, `APP_ENROL_SCREEN_READY` in `toms_web/src/lib/pairing.ts`, which is `false`. **The app PR sets it to `true`**, which removes the note and changes the panel to "choose Scan to enrol… or type the code"; a test covers both readings.
- Not on the page: a way to see a phone's token (there is none), a way to un-revoke (there is none, by design), and any change to how the server issues or checks codes.

## 7. For the app step

The rules below are the ones asked for; none of them is built yet.

1. **Scanner library:** `com.journeyapps:zxing-android-embedded:4.3.0`, which decodes with ZXing core 3.4.1; both are Apache License 2.0. It does not use Google Play services or ML Kit, and the app has none (see the pull request for how this was checked).
2. **Parse strictly.** Accept only `toms-enrol://v1` with exactly one `server` and one `code`. The server must be an absolute URL with a host and no credentials in it.
3. **Release builds accept an `https` server only.** Debug builds may accept `http`, as their network security config already does.
4. **Ask before sending anything.** Show the server's host in full and ask "Enrol with this server?". Nothing goes to the network, and nothing is saved, until the person says yes. A QR must never silently point the phone at another server. Suggested: when a server address is already set on More and the host differs, say so in the dialog.
5. **Send only** `{code, device_id}` to `<server>/api/devices/pair`, where the Device ID is the phone's own. The app has not changed OkHttp's default of following redirects, so the pairing call should not follow them: the code and Device ID should only ever go to the host the person confirmed.
6. **Store the result** with the existing `setServerUrl` and `setDeviceToken`. Never log the code, the QR text or the token. The app's HTTP log level is `BASIC`: request and response lines only, with the URL, but no headers or bodies. That is why the code travels in the request body and never in the URL (the web servers' access logs record the URL too). The log level must not be raised for this call.
7. **Typed code, always.** Offer it next to the scanner, not only after the camera fails. It must work without the camera permission and without the scanner screen, and it uses the server address already set on More (or asks for one, under the same HTTPS and confirmation rules).
8. **The camera is optional.** The library's manifest declares `android.hardware.camera.any` without `required="false"`, and the default is required. Android's own documentation says the system does not check declared features when an app is installed, but an app store or a device-management console that does would treat the app as camera-only. Declare the feature optional in the app's manifest, then read the merged result (`aapt2 dump badging` on the built APK, as the Android documentation describes) rather than assuming the merge went the right way.
9. **Say plainly** what each answer means: 403 (check the code or ask for a new one), 409, 429 (wait), and no connection.

## 8. Not tested

- Nothing on a phone. The two phone checks (the camera permission inside the work profile, and the typed-code fallback) are on the unticked list in `work_profile_setup.md`, section 4.
- The live server. The route is new and public (reachable from the internet through Caddy and nginx like the rest of `/api`).
- Many addresses guessing at once: the per-address counter is exercised from one address only.
- A restart in the middle of a pairing. The use of a code and the creation of the device happen in one database transaction.
