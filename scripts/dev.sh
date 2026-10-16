#!/usr/bin/env bash
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
require_java
require_docker
load_environment

if [[ "${ASO_PORT:-8080}" != 8080 ]]; then
  echo "The Angular development proxy expects ASO_PORT=8080. Update frontend/proxy.conf.json for another port." >&2
  exit 1
fi

docker compose up --detach --wait database keycloak
SPRING_PROFILES_ACTIVE=development ./gradlew bootRun &
aso_backend_pid=$!
cleanup() {
  kill "$aso_backend_pid" 2>/dev/null || true
  wait "$aso_backend_pid" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
echo "Starting ASO: frontend http://127.0.0.1:4200, backend http://127.0.0.1:8080"
./gradlew frontendStart
