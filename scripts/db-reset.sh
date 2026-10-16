#!/usr/bin/env bash
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
require_docker
if [[ "${1:-}" != "--confirm-delete-local-data" ]]; then
  echo "This deletes ASO's local database volume. Stop ASO, then run:"
  echo "./scripts/db-reset.sh --confirm-delete-local-data"
  exit 1
fi
load_environment
docker compose down --volumes
docker compose up --detach --wait database
