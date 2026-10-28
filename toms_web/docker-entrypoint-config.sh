#!/bin/sh
# Builds the nginx config when the container starts.
#
# Local Docker (default): Web NFC only works in a secure context, so the dashboard is served over HTTPS
# with a self-signed certificate kept on a volume. Phones accept the browser warning once.
#
# Hosted behind a platform that already terminates HTTPS (for example Railway): set TLS_MODE=off.
# nginx then serves plain HTTP on $PORT (default 8080).
#
# API_UPSTREAM is where /api, /uploads and /socket.io are forwarded. Default http://api:3000 matches
# docker compose; on Railway use the API service's private address, e.g. http://api.railway.internal:3000.
set -e

export API_UPSTREAM="${API_UPSTREAM:-http://api:3000}"

if [ "${TLS_MODE:-self-signed}" = "off" ]; then
  WEB_PORT="${PORT:-8080}"
  SERVER_LISTEN="listen ${WEB_PORT};"
  if [ -f /proc/net/if_inet6 ]; then SERVER_LISTEN="$SERVER_LISTEN listen [::]:${WEB_PORT};"; fi
else
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
  SERVER_LISTEN="listen 443 ssl; ssl_certificate $CERT_DIR/tls.crt; ssl_certificate_key $CERT_DIR/tls.key;"
fi
export SERVER_LISTEN

# Set by the stock 15-local-resolvers.envsh when NGINX_ENTRYPOINT_LOCAL_RESOLVERS is on.
if [ -z "$NGINX_LOCAL_RESOLVERS" ]; then
  NGINX_LOCAL_RESOLVERS=$(awk 'BEGIN{ORS=" "} $1=="nameserver" {if ($2 ~ ":") {print "["$2"]"} else {print $2}}' /etc/resolv.conf)
fi
export NGINX_LOCAL_RESOLVERS="${NGINX_LOCAL_RESOLVERS% }"

envsubst '${SERVER_LISTEN} ${API_UPSTREAM} ${NGINX_LOCAL_RESOLVERS}' \
  < /etc/nginx/toms.conf.template > /etc/nginx/conf.d/default.conf
