#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

compose() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "$@"
  elif command -v docker-compose >/dev/null 2>&1; then
    docker-compose "$@"
  else
    echo "Need Docker Compose on this host: install docker-compose-plugin" >&2
    exit 1
  fi
}

# Start Mongo if needed. Do not recreate a running database.
compose up -d mongo mongo-express
# Recreate only the app so the new jar is picked up.
compose up -d --force-recreate --no-deps ttk
