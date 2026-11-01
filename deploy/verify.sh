#!/usr/bin/env bash
# Checks a live TOMS deployment from outside. Read-only: no logins, no writes, no admin or device credentials.
#
#   bash deploy/verify.sh https://toms.perchbuilds.me
#
# Prints one PASS or FAIL line per check and exits 1 if any check fails. It needs only curl.
# The port scan is not run here: the script prints the command to run yourself.
set -uo pipefail

BASE="${1:-}"
if [ -z "$BASE" ]; then
  echo "usage: bash deploy/verify.sh https://your-server" >&2
  exit 2
fi
BASE="${BASE%/}"
case "$BASE" in
  https://*) ;;
  *) echo "FAIL  address must start with https:// (got: $BASE)" >&2; exit 2 ;;
esac

HOST="${BASE#https://}"
HOST="${HOST%%/*}"
HOST_ONLY="${HOST%%:*}"
FAILED=0
CURL_OPTS=(--silent --show-error --max-time 15 --connect-timeout 10)
# Only for the script's own tests against a local server with a self-signed certificate. Leave it unset for a real check.
if [ -n "${TOMS_VERIFY_CACERT:-}" ]; then
  CURL_OPTS+=(--cacert "$TOMS_VERIFY_CACERT")
fi

pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; FAILED=$((FAILED + 1)); }

# Fetches a path and stores the status, the content type and the full headers in shared variables.
fetch() {
  local path="$1"
  shift
  HEADERS=$(curl "${CURL_OPTS[@]}" -D - -o /tmp/toms-verify-body.$$ -w '%{http_code}' "$@" "$BASE$path" 2>/tmp/toms-verify-err.$$) || true
  STATUS="${HEADERS: -3}"
  HEADERS="${HEADERS%???}"
  BODY=$(head -c 4096 /tmp/toms-verify-body.$$ 2>/dev/null || true)
  rm -f /tmp/toms-verify-body.$$ /tmp/toms-verify-err.$$
}

header() { printf '%s' "$HEADERS" | tr -d '\r' | grep -i "^$1:" | head -n 1 | cut -d: -f2- | sed 's/^ //'; }

# --- 1. Certificate and redirect -----------------------------------------------------------
# A valid certificate is checked without -k: curl fails on an untrusted or expired certificate.
if curl "${CURL_OPTS[@]}" -o /dev/null "$BASE/" >/dev/null 2>&1; then
  pass "HTTPS certificate is valid for $HOST_ONLY (curl without -k)"
else
  fail "HTTPS certificate is not valid for $HOST_ONLY, or the server is unreachable"
fi

HTTP_STATUS=$(curl "${CURL_OPTS[@]}" -o /dev/null -w '%{http_code} %{redirect_url}' "http://$HOST_ONLY/" 2>/dev/null || true)
case "$HTTP_STATUS" in
  30[12678]\ https://*) pass "HTTP redirects to HTTPS ($HTTP_STATUS)" ;;
  *) fail "HTTP does not redirect to HTTPS (got: ${HTTP_STATUS:-no answer})" ;;
esac

# --- 2. The app and the public health route -----------------------------------------------
fetch "/"
if [ "$STATUS" = "200" ] && printf '%s' "$(header Content-Type)" | grep -qi 'text/html'; then
  pass "/ returns 200 HTML (the dashboard)"
else
  fail "/ should return 200 HTML, got $STATUS $(header Content-Type)"
fi

fetch "/api/health"
if [ "$STATUS" = "200" ] && printf '%s' "$BODY" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"ok"'; then
  pass "/api/health returns 200 with status ok"
else
  fail "/api/health should return 200 with status ok, got $STATUS (not deployed yet, or the database is down)"
fi

# --- 3. Protected endpoints answer 401 without a login ------------------------------------
fetch "/api/config"
if [ "$STATUS" = "401" ]; then
  pass "/api/config returns 401 without a login"
else
  fail "/api/config should return 401 without a login, got $STATUS"
fi

fetch "/api/device/config"
if [ "$STATUS" = "401" ]; then
  pass "/api/device/config returns 401 without a device token"
else
  fail "/api/device/config should return 401 without a device token, got $STATUS"
fi

# --- 4. Malformed public links are client errors, not server errors --------------------------
fetch "/api/receipt/not-a-uuid"
if [ "$STATUS" -ge 400 ] 2>/dev/null && [ "$STATUS" -lt 500 ] 2>/dev/null; then
  pass "/api/receipt/not-a-uuid returns a 4xx ($STATUS), not a 5xx"
else
  fail "/api/receipt/not-a-uuid should return a 4xx, got $STATUS"
fi

# A card link is answered by the app itself (HTML) or by the API (a 4xx JSON for a bad id). Either is fine.
fetch "/r/not-a-uuid" -H 'Accept: application/json'
if [ "$STATUS" -ge 400 ] 2>/dev/null && [ "$STATUS" -lt 500 ] 2>/dev/null; then
  pass "/r/not-a-uuid returns a 4xx for JSON clients ($STATUS)"
elif [ "$STATUS" = "200" ] && printf '%s' "$(header Content-Type)" | grep -qi 'text/html'; then
  pass "/r/not-a-uuid is served by the app (200 HTML), not a server error"
else
  fail "/r/not-a-uuid should be a 4xx or the app page, got $STATUS"
fi

# --- 5. Security headers ----------------------------------------------------------------------
fetch "/"
if [ -n "$(header Strict-Transport-Security)" ]; then
  pass "Strict-Transport-Security is set"
else
  fail "Strict-Transport-Security header is missing"
fi

if [ "$(header X-Content-Type-Options | tr '[:upper:]' '[:lower:]')" = "nosniff" ]; then
  pass "X-Content-Type-Options is nosniff"
else
  fail "X-Content-Type-Options should be nosniff"
fi

if [ -n "$(header Content-Security-Policy)" ] || [ -n "$(header Content-Security-Policy-Report-Only)" ]; then
  pass "a Content-Security-Policy header is set (enforcing or report-only)"
else
  fail "no Content-Security-Policy header"
fi

if [ -z "$(header X-Powered-By)" ]; then
  pass "X-Powered-By is absent"
else
  fail "X-Powered-By is present: $(header X-Powered-By)"
fi

# --- 6. Ports: a reminder, not a scan --------------------------------------------------------
echo "NOTE  not scanned here. Run this from another machine and check that only 22, 80 and 443 answer:"
echo "      nmap -Pn $HOST_ONLY"

echo
if [ "$FAILED" -eq 0 ]; then
  echo "All checks passed."
  exit 0
fi
echo "$FAILED check(s) failed."
exit 1
