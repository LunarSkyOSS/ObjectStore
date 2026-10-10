#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p out/classes
javac --release 21 --add-modules jdk.httpserver,java.net.http -cp lib/hash4j-0.30.0.jar -d out/classes \
  src/cloud/lunarsky/store/*.java test/cloud/lunarsky/store/*.java
java --add-modules jdk.httpserver -cp out/classes:lib/hash4j-0.30.0.jar cloud.lunarsky.store.StoreTest
java --add-modules jdk.httpserver -cp out/classes:lib/hash4j-0.30.0.jar cloud.lunarsky.store.ConcurrencyTest
java --add-modules jdk.httpserver,java.net.http -cp out/classes:lib/hash4j-0.30.0.jar cloud.lunarsky.store.HttpTest
java --add-modules jdk.httpserver,java.net.http -cp out/classes:lib/hash4j-0.30.0.jar cloud.lunarsky.store.ClientLimitsTest
java --add-modules jdk.httpserver,java.net.http -cp out/classes:lib/hash4j-0.30.0.jar cloud.lunarsky.store.ClusterNodeTest
java --add-modules jdk.httpserver,java.net.http -cp out/classes:lib/hash4j-0.30.0.jar cloud.lunarsky.store.ClusterTlsTest
java -cp out/classes:lib/hash4j-0.30.0.jar cloud.lunarsky.store.CliTest
bash client/scripts/test.sh
