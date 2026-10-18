#!/usr/bin/env bash
# Upgrade the LOCAL fixture to one public application client without resetting users or data.
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
load_environment
export ASO_KEYCLOAK_PORT ASO_KEYCLOAK_ADMIN ASO_KEYCLOAK_ADMIN_PASSWORD
export ASO_WEB_CALLBACK ASO_WEB_LOGOUT ASO_MCP_RESOURCE ASO_CODEX_CALLBACK_URL ASO_MCP_JAM_CALLBACK_URL
python3 scripts/keycloak-configure-identity.py
