#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .gcp-build
cp backend/build/libs/aso.jar .gcp-build/aso.jar
cp infra/gcp/Dockerfile .gcp-build/Dockerfile
gcloud builds submit .gcp-build \
  --project=agentic-stack-overfl-mka5b8 \
  --region=europe-west1 \
  --config=infra/gcp/cloudbuild.yaml \
  --gcs-source-staging-dir=gs://agentic-stack-overfl-mka5b8-aso-build/source \
  "$@"
