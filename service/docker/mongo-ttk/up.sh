#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
if docker compose version >/dev/null 2>&1; then
  docker compose up -d --force-recreate
elif command -v docker-compose >/dev/null 2>&1; then
  docker-compose up -d --force-recreate
else
  echo "Need Docker Compose on this host: install docker-compose-plugin" >&2
  exit 1
fi
