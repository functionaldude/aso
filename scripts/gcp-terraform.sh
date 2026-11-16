#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../infra/gcp"
export GOOGLE_OAUTH_ACCESS_TOKEN
GOOGLE_OAUTH_ACCESS_TOKEN="$(gcloud auth print-access-token --project=agentic-stack-overfl-mka5b8)"
export GOOGLE_PROJECT=agentic-stack-overfl-mka5b8
terraform "$@"
