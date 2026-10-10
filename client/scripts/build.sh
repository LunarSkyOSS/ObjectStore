#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf dist/classes
mkdir -p dist/classes
find src/main/java -name '*.java' -print0 |
  xargs -0 javac --release 21 -d dist/classes
mkdir -p dist/classes/META-INF
cp ../LICENSE dist/classes/META-INF/LICENSE
jar --create --file dist/objectstore-client.jar -C dist/classes .
javadoc --release 21 -quiet -Xdoclint:reference,syntax,html -d dist/javadoc \
  $(find src/main/java -name '*.java' -print)
echo "Built dist/objectstore-client.jar"
