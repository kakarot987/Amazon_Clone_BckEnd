#!/usr/bin/env bash
# Creates a unique synthetic product and waits for eventual composite visibility.
# Requires a signed-in user JWT with product:read and product:write.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8443}"
ATTEMPTS="${ATTEMPTS:-40}"
INTERVAL="${INTERVAL:-3}"
PRODUCT_ID="${PRODUCT_ID:-$((100000 + (RANDOM * 32768 + RANDOM) % 1900000000))}"

if [[ -z "${ACCESS_TOKEN:-}" ]]; then
  echo "Set ACCESS_TOKEN to a signed-in local OAuth2 JWT with product:read and product:write." >&2
  exit 2
fi
if [[ ! "$PRODUCT_ID" =~ ^[1-9][0-9]*$ ]] || (( ${#PRODUCT_ID} > 10 )) || (( PRODUCT_ID > 2147483647 )); then
  echo "PRODUCT_ID must be a positive Java int." >&2
  exit 2
fi
if [[ ! "$ATTEMPTS" =~ ^[1-9][0-9]*$ ]] || [[ ! "$INTERVAL" =~ ^[1-9][0-9]*$ ]]; then
  echo "ATTEMPTS and INTERVAL must be positive integers." >&2
  exit 2
fi
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

echo "Checking gateway health and anonymous access..."
curl --fail --silent --show-error --max-time 10 "$BASE_URL/actuator/health" > /dev/null
anonymous="$(curl --silent --show-error --max-time 10 --output /dev/null --write-out '%{http_code}' \
  "$BASE_URL/product-composite/$PRODUCT_ID" || true)"
if [[ "$anonymous" != 401 && "$anonymous" != 403 ]]; then
  echo "Expected anonymous catalog denial (401/403); got $anonymous." >&2
  exit 1
fi

payload="$(sed 's/"productId": 900001/"productId": '"$PRODUCT_ID"'/' "$ROOT/samples/product-demo.json")"
echo "Persisting synthetic aggregate for product $PRODUCT_ID..."
created="$(curl --silent --show-error --max-time 25 --output "$tmp" --write-out '%{http_code}' \
  -X POST "$BASE_URL/product-composite" \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  --data "$payload" || true)"
if [[ "$created" != 202 ]]; then
  echo "Expected 202 Accepted (durable outbox intent), got $created." >&2
  cat "$tmp" >&2
  exit 1
fi
echo "HTTP 202 Accepted. This is not downstream completion."

for (( i=1; i<=ATTEMPTS; i++ )); do
  status="$(curl --silent --show-error --max-time 15 --output "$tmp" --write-out '%{http_code}' \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    "$BASE_URL/product-composite/$PRODUCT_ID" || true)"
  if [[ "$status" == 200 ]]; then
    echo "Composite GET returned 200 for product $PRODUCT_ID (attempt $i)."
    cat "$tmp"
    printf '\n'
    echo "Check recommendation/review fan-out in the JSON response before recording."
    exit 0
  fi
  if [[ "$status" == 401 || "$status" == 403 ]]; then
    echo "JWT rejected/expired while polling (HTTP $status)." >&2
    exit 1
  fi
  sleep "$INTERVAL"
done
echo "Composite product did not become readable after $ATTEMPTS attempts; inspect outbox, broker and consumer logs." >&2
exit 1
