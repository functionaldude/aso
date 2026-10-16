#!/usr/bin/env bash
# Add goal 001 client registrations to an existing LOCAL fixture without resetting users or data.
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
load_environment
export ASO_KEYCLOAK_PORT ASO_KEYCLOAK_ADMIN ASO_KEYCLOAK_ADMIN_PASSWORD
python3 scripts/keycloak-configure-identity.py
