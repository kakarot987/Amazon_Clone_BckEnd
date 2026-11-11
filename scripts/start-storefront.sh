#!/usr/bin/env bash
# One-command startup for the backend and lightweight storefront.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ ! -f .env ]]; then
  echo "Missing .env. Run: cp .env.example .env, then replace every placeholder." >&2
  exit 2
fi
if ! command -v docker >/dev/null; then
  echo "Docker and the Docker Compose v2 plugin are required." >&2
  exit 2
fi
docker compose version >/dev/null

compose=(docker compose -f docker-compose.yml -f docker-compose-frontend.yml)
case "${1:-up}" in
  up)
    "${compose[@]}" config --quiet
    "${compose[@]}" up --build -d
    echo
    echo "Storefront: http://localhost:8088"
    echo "Gateway:   http://localhost:8443"
    echo "Status:    bash scripts/start-storefront.sh status"
    ;;
  status)
    "${compose[@]}" ps
    ;;
  logs)
    "${compose[@]}" logs --tail=100 -f storefront gateway product-composite
    ;;
  down)
    "${compose[@]}" down
    ;;
  *)
    echo "Usage: bash scripts/start-storefront.sh [up|status|logs|down]" >&2
    exit 2
    ;;
esac
