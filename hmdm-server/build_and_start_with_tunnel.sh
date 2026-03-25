#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$ROOT_DIR/docker-compose.yml"
ENV_FILE="$ROOT_DIR/.env"

if ! command -v docker >/dev/null 2>&1; then
  echo "Error: docker is not installed or not in PATH."
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "Error: docker compose is not available."
  exit 1
fi

if [ ! -f "$COMPOSE_FILE" ]; then
  echo "Error: docker-compose.yml not found in $ROOT_DIR"
  exit 1
fi

if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
fi

CLOUDFLARE_TUNNEL_TOKEN="${CLOUDFLARE_TUNNEL_TOKEN:-eyJhIjoiMTk1ZDcwNWZlMzI5YTQ3MzZkMTczMzQ1MDAwYmI3NGIiLCJ0IjoiNDVhYTIwNmMtNWQ3YS00MzIzLWE5ZjctNDkxNDQ4MWNiZWI4IiwicyI6Ik1XUTFOalpsWlRVdFkyVXdOQzAwWlRJMkxUazFOV0l0WldKaU5qWTVOekptWkRSaiJ9}"
CLOUDFLARE_HOSTNAME="${CLOUDFLARE_HOSTNAME:-brothers-mdm.com}"
BASE_URL="${BASE_URL:-https://$CLOUDFLARE_HOSTNAME}"

if [ -z "$CLOUDFLARE_TUNNEL_TOKEN" ]; then
  echo "Error: CLOUDFLARE_TUNNEL_TOKEN is empty."
  exit 1
fi

echo "Building images and starting services (postgres, hmdm, cloudflared)..."
cd "$ROOT_DIR"
docker compose up -d --build

echo
echo "Service status:"
docker compose ps

echo
echo "Panel URL: $BASE_URL"
echo "Tunnel hostname: https://$CLOUDFLARE_HOSTNAME"
echo "To watch tunnel logs: docker compose logs -f cloudflared"
