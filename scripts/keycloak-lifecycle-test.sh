#!/usr/bin/env bash
# Destructive isolation checks run only in a throwaway copy/project, never the owner's volumes.
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
require_docker
aso_test_root="$(mktemp -d "${TMPDIR:-/tmp}/aso-lifecycle.XXXXXX")"
aso_test_project="aso-lifecycle-$(date +%s)-$$"
cleanup() {
  local result=$?
  if ! docker compose --project-name "$aso_test_project" --env-file /dev/null \
    -f "$aso_test_root/compose.yaml" -f "$aso_test_root/override.yaml" down --volumes >/dev/null 2>&1; then
    echo "Failed to clean disposable project $aso_test_project; configuration retained at $aso_test_root." >&2
    return 1
  fi
  rm -rf -- "$aso_test_root"
  return "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir -p "$aso_test_root/scripts" "$aso_test_root/dev/keycloak"
cp scripts/common.sh scripts/db-reset.sh scripts/keycloak-{start,stop,status,reset}.sh "$aso_test_root/scripts/"
cp compose.yaml "$aso_test_root/"
cp dev/keycloak/aso-dev-realm.json "$aso_test_root/dev/keycloak/"
cat > "$aso_test_root/override.yaml" <<'YAML'
services:
  database:
    ports: !override ["127.0.0.1::5432"]
    environment:
      POSTGRES_USER: aso
      POSTGRES_PASSWORD: aso-local-only
  keycloak:
    ports: !override ["127.0.0.1::8080"]
YAML
(
  cd -- "$aso_test_root"
  export COMPOSE_PROJECT_NAME="$aso_test_project" COMPOSE_FILE="$aso_test_root/compose.yaml:$aso_test_root/override.yaml" COMPOSE_ENV_FILES=/dev/null
  unset ASO_DB_USER ASO_DB_PASSWORD ASO_KEYCLOAK_PORT ASO_KEYCLOAK_ADMIN ASO_KEYCLOAK_ADMIN_PASSWORD
  dc() { docker compose "$@"; }
  sql() { dc exec -T database psql -U aso -d aso -v ON_ERROR_STOP=1 -Atc "$1"; }
  dc up --detach --wait
  database_id="$(dc ps --quiet database)"
  keycloak_id="$(dc ps --quiet keycloak)"
  sql "CREATE TABLE reset_sentinel (value text); INSERT INTO reset_sentinel VALUES ('keep');"
  dc exec -T keycloak bash -c 'echo keep > /opt/keycloak/data/reset-sentinel'
  ./scripts/keycloak-stop.sh
  [[ "$(dc ps --quiet database)" == "$database_id" ]]
  ./scripts/keycloak-start.sh
  [[ "$(dc ps --quiet database)" == "$database_id" ]]
  dc exec -T keycloak test -f /opt/keycloak/data/reset-sentinel
  [[ "$(sql 'SELECT value FROM reset_sentinel')" == keep ]]
  if ./scripts/db-reset.sh; then echo 'Unconfirmed database reset succeeded' >&2; exit 1; fi
  if ./scripts/keycloak-reset.sh; then echo 'Unconfirmed Keycloak reset succeeded' >&2; exit 1; fi
  ./scripts/db-reset.sh --confirm-delete-local-data
  [[ "$(dc ps --quiet keycloak)" == "$keycloak_id" ]]
  dc exec -T keycloak test -f /opt/keycloak/data/reset-sentinel
  [[ "$(sql "SELECT count(*) FROM pg_tables WHERE tablename = 'reset_sentinel'")" == 0 ]]
  sql "CREATE TABLE reset_sentinel (value text); INSERT INTO reset_sentinel VALUES ('keep');"
  database_id="$(dc ps --quiet database)"
  ./scripts/keycloak-reset.sh --confirm-delete-local-keycloak-data
  [[ "$(dc ps --quiet database)" == "$database_id" ]]
  [[ "$(sql 'SELECT value FROM reset_sentinel')" == keep ]]
  dc exec -T keycloak test ! -f /opt/keycloak/data/reset-sentinel
  echo 'PASS: confirmation guards, service-specific stop/start, restart persistence, independent database/Keycloak resets.'
)
