#!/usr/bin/env bash
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
require_docker
load_environment
if [[ "${1:-}" != "--confirm-delete-local-keycloak-data" ]]; then
  echo "This deletes only local Keycloak state and reseeds the realm. Run:"
  echo "./scripts/keycloak-reset.sh --confirm-delete-local-keycloak-data"
  exit 1
fi
reset_service keycloak
docker compose up --detach --wait --no-deps keycloak
