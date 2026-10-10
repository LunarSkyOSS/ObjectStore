#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
build_dir="$(mktemp -d)"
trap 'rm -rf "$build_dir"' EXIT
find src/main/java src/test/java -name '*.java' -print0 |
  xargs -0 javac --release 21 -d "$build_dir"
java -cp "$build_dir" cloud.lunarsky.objectstore.client.ClientTest
java -cp "$build_dir" cloud.lunarsky.objectstore.client.MultipartClientTest
