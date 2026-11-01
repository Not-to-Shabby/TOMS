TITLE: Pilot setup: public health route, live check, and a pilot setup script

BASE: main

Four commits, each with its tests. Nothing here changes the Content-Security-Policy, the Android app, the compose files, backup.sh or secret handling.

## 1. Public health route (7b90e71)
- `GET /api/health` runs `SELECT 1` and answers `{"status":"ok"}` with 200. If the database fails it answers 503 `{"status":"error"}` with no error text.
- It sits under the existing nginx rule for `/api/`, so the web container proxies it to the API. The nginx template is unchanged. The old `/health` still exists.
- It reveals no version, build, configuration or timestamp, and it is not counted against the card and receipt rate limit.
- Tests: 200 with a working database, 503 with no detail when the query throws, the body holds only `status`, the limiter does not apply, and `/health` still works.

## 2. Read-only check of the live server (e7254c7)
- `bash deploy/verify.sh https://toms.perchbuilds.me` prints one PASS or FAIL line per check and exits 1 if any fail. It needs only curl.
- It makes read-only, unauthenticated requests: no logins, no writes, no admin or device credentials.
- It checks the certificate without `-k`, the HTTP-to-HTTPS redirect, `/` (200 HTML), `/api/health` (200, ok), `/api/config` and `/api/device/config` (401 without a login), the malformed receipt link (a 4xx), the malformed card link (a 4xx, or the app page), the HSTS, nosniff and CSP headers, and that `X-Powered-By` is absent.
- It prints the nmap command for the port check and does not run the scan itself.
- Tested against a local TLS mock: the good mock passes the HTTPS checks, and the bad mock fails the checks it breaks and exits 1.

## 3. Pilot setup script (c69aac2, with fixes in the next commit)
- `node scripts/setup-pilot.mjs --server https://toms.perchbuilds.me` creates the vehicle, the conductor on it, the route with its path and stops, the route's vehicle link, and a published fare matrix, through the admin API. `--dry-run` validates the data and sends nothing. `enrol-device <device-id> <vehicle-id>` enrols a phone and prints its token once.
- `--server` is required, has no default, and must be https.
- Validation runs before anything is sent, and lists every problem.
- Re-running creates only what is missing. A conductor already on another bus is refused, never moved.
- The admin username is shown as it is typed. The admin password and a new conductor's password are hidden. Passwords can also come from `TOMS_ADMIN_PASSWORD` and `TOMS_CONDUCTOR_PASSWORD`. None of them is ever in the data file, the plan, the log or an error.
- Fares go to the API in pesos, and the rounding step stays in centavos.
- `scripts/pilot-data.example.json` holds only fake values. `scripts/pilot-data.json` is git-ignored.

### Found by testing against the real API (the mocked tests had passed)
- A new conductor needs a password, which the script did not send.
- The conductor list reports `"N/A"` for a conductor with no vehicle, not null.
- The vehicle update clears the conductor link unless the conductor is sent too, so attaching the route would have unlinked the conductor. The script now sends the current conductor with the route.

### Found in review of this PR (fixed in the last commit)
- The password reader never turned on raw mode or resumed input, so a real terminal would have sent nothing until Enter. Fixed.
- The reader handled only string input, so a Buffer chunk would never match a character. Both readers now accept either.
- The username was hidden like the password. Now it is shown as it is typed.

## Tests run (on the final code)
- Backend: 307 tests across 15 files, `tsc` clean. Includes the health tests and an integration test that runs the setup script against the real API and the test database, and checks the fare the phone downloads and the conductor on the vehicle.
- Web: 311 tests across 20 files, `tsc -b` clean. No web code changed on this branch.
- Script: 29 unit tests with `node --test scripts/setup-pilot.test.mjs`: 29 pass, 0 fail, 0 cancelled, exit 0.
- `node --check` on the script and its tests, and `bash -n deploy/verify.sh` on the committed file.
- Earlier, I broke three behaviours on purpose (the conductor refusal, the fare skip, password leakage), and the tests failed each time. I did not repeat that after the reader fixes.

## Not tested, and why
- **`deploy/verify.sh` against the live site with the health route.** The live site runs code from before this branch, so `/api/health` returns 404 there. Before deploy, the live run gave 11 pass and 1 fail, and the fail is the health route. It passes once this branch is deployed.
- **The setup script against the live server.** Nothing was sent to `toms.perchbuilds.me`. Run the dry run first, then the real run.
- **The hidden prompts in a real terminal.** This environment has no terminal, so the reader was tested with a fake terminal and with the environment-variable path. Run the prompts once by hand.
- **Enrolment against the live server.** Covered by the integration test on the test database only.
- **The nmap port check.** Not run. The script prints the command to run from another machine.
- **Lint on the new script.** There is no ESLint config for `scripts/`, and `shellcheck` is not installed here, so `verify.sh` has only `bash -n`.

## Before the owner runs it
- Deploy this branch so `/api/health` exists on the server.
- Keep `scripts/pilot-data.json` out of git (it is ignored).
- The data file sets the route, stops and fares. Set `fare.document_path` only if an LTFRB document should be uploaded.
- The conductor's password is asked for at the prompt on the first run, and it is not stored anywhere.
- The runbook (`deploy/DEPLOY.md`) now lists both tools in one place.
