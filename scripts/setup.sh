#!/usr/bin/env bash
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
require_java
require_docker
./gradlew --no-daemon nodeSetup npmInstall
echo "Toolchain ready. Run ./scripts/dev.sh to start ASO."
