#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
project="objectstore-metadata-test-$$"
compose() { docker compose -p "$project" -f tests/metadata/compose.yaml "$@"; }
trap 'compose down -v --remove-orphans >/dev/null 2>&1 || true' EXIT
compose up --build --abort-on-container-exit --exit-code-from check check
