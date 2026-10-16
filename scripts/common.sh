#!/usr/bin/env bash
set -euo pipefail

aso_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$aso_root"

require_java() {
  local java_command="java"
  if [[ -n "${JAVA_HOME:-}" ]]; then java_command="$JAVA_HOME/bin/java"; fi
  local java_version
  if ! java_version="$("$java_command" -version 2>&1)" || [[ ! "$java_version" =~ version\ \"25([.\"+-]) ]]; then
    echo "ASO requires Java 25. Set JAVA_HOME or PATH to a Java 25 JDK." >&2
    exit 1
  fi
}

require_docker() {
  if ! command -v docker >/dev/null || ! docker info >/dev/null 2>&1; then
    echo "Start Docker Desktop (or a compatible Docker engine) before running this command." >&2
    exit 1
  fi
  docker compose version >/dev/null
}

load_environment() {
  # .env is trusted local shell configuration; tests and Gradle never load it.
  if [[ -f .env ]]; then
    set -a
    source .env
    set +a
  fi
}
