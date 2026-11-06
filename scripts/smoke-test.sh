#!/usr/bin/env bash
# Smoke test the local gateway after Docker Compose startup.
# Use OAuth2 client credentials / authorization-code flow to supply a token.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8443}"
TIMEOUT="${TIMEOUT:-180}"
INTERVAL="${INTERVAL:-3}"

echo "Checking gateway health at ${BASE_URL}/actuator/health"
end=$((SECONDS + TIMEOUT))
until curl --fail --silent --show-error --max-time 5 "${BASE_URL}/actuator/health" > /dev/null 2>&1; do
  if (( SECONDS >= end )); then
    echo "Gateway did not become healthy. Inspect: docker compose logs gateway" >&2
    exit 1
  fi
  sleep "$INTERVAL"
done
echo "Gateway health: OK"

# Unauthorized calls must never expose catalog data.
status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  --max-time 10 "${BASE_URL}/product-composite/1")
case "$status" in
  401|403) echo "Unauthorized catalog access: rejected ($status)" ;;
  *) echo "Unexpected unauthenticated status: $status" >&2; exit 1 ;;
esac

if [[ -z "${ACCESS_TOKEN:-}" ]]; then
  echo "Authenticated catalog check skipped: set ACCESS_TOKEN to run it."
  exit 0
fi

if [[ -z "${PRODUCT_ID:-}" ]]; then
  echo "ACCESS_TOKEN supplied; set PRODUCT_ID to an existing product for a read check."
  exit 0
fi

status=$(curl --silent --output /dev/null --write-out '%{http_code}' \
  --max-time 10 --header "Authorization: Bearer ${ACCESS_TOKEN}" \
  "${BASE_URL}/product-composite/${PRODUCT_ID}")
if [[ "$status" != 200 ]]; then
  echo "Authenticated product read returned $status; expected 200" >&2
  exit 1
fi

echo "Authenticated product read: OK"
