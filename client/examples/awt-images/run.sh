#!/usr/bin/env bash
set -euo pipefail
client_dir="$(cd "$(dirname "$0")/../.." && pwd)"
if [[ ! -f "$client_dir/dist/objectstore-client.jar" ]]; then
  bash "$client_dir/scripts/build.sh"
fi
classes="$client_dir/dist/examples/awt-images"
mkdir -p "$classes"
javac --release 21 -cp "$client_dir/dist/objectstore-client.jar" \
  -d "$classes" "$client_dir/examples/awt-images/ImageManager.java"
exec java -cp "$classes:$client_dir/dist/objectstore-client.jar" ImageManager
