#!/bin/sh
# Web NFC only works in a secure context, so the dashboard is served over HTTPS.
# The self-signed certificate lives on a volume so it stays the same across restarts
# and phones only have to accept the browser warning once.
set -e
CERT_DIR=/etc/nginx/certs
if [ ! -f "$CERT_DIR/tls.crt" ] || [ ! -f "$CERT_DIR/tls.key" ]; then
  mkdir -p "$CERT_DIR"
  SAN="DNS:localhost,IP:127.0.0.1"
  if [ -n "$TLS_EXTRA_IPS" ]; then
    for ip in $(echo "$TLS_EXTRA_IPS" | tr ',' ' '); do SAN="$SAN,IP:$ip"; done
  fi
  openssl req -x509 -nodes -newkey rsa:2048 -days 825 \
    -keyout "$CERT_DIR/tls.key" -out "$CERT_DIR/tls.crt" \
    -subj "/CN=toms-local" -addext "subjectAltName=$SAN"
fi
