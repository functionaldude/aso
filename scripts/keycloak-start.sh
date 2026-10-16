#!/usr/bin/env bash
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
require_docker
load_environment
docker compose up --detach --wait --no-deps keycloak
