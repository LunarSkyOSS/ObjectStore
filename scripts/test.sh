#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p out/classes
javac --release 21 --add-modules jdk.httpserver,java.net.http -d out/classes \
  src/cloud/lunarsky/store/*.java test/cloud/lunarsky/store/*.java
java --add-modules jdk.httpserver -cp out/classes cloud.lunarsky.store.StoreTest
java --add-modules jdk.httpserver -cp out/classes cloud.lunarsky.store.ConcurrencyTest
java --add-modules jdk.httpserver,java.net.http -cp out/classes cloud.lunarsky.store.HttpTest
java --add-modules jdk.httpserver,java.net.http -cp out/classes cloud.lunarsky.store.ClusterNodeTest
java -cp out/classes cloud.lunarsky.store.CliTest
