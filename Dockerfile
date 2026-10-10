FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src
RUN wget -q -O /tmp/postgresql.jar https://jdbc.postgresql.org/download/postgresql-42.7.14.jar && \
    echo '73914527305a40cce504b0d3d90b23caf565136912d607ae8ae7c5895512332c  /tmp/postgresql.jar' | sha256sum -c -
COPY src ./src
COPY test ./test
COPY lib/hash4j-0.30.0.jar /tmp/hash4j.jar
RUN echo 'd3224b113ea835ce3452097747e5209ec760cf443dbed770d6ba68a0d480178f  /tmp/hash4j.jar' | sha256sum -c -
RUN mkdir /out && javac --release 21 --add-modules jdk.httpserver,java.net.http -cp /tmp/hash4j.jar -d /out src/cloud/lunarsky/store/*.java test/cloud/lunarsky/store/*.java
RUN java --add-modules jdk.httpserver -cp /out:/tmp/hash4j.jar cloud.lunarsky.store.StoreTest
RUN java --add-modules jdk.httpserver -cp /out:/tmp/hash4j.jar cloud.lunarsky.store.ConcurrencyTest
RUN java --add-modules jdk.httpserver,java.net.http -cp /out:/tmp/hash4j.jar cloud.lunarsky.store.HttpTest
RUN java --add-modules jdk.httpserver,java.net.http -cp /out:/tmp/hash4j.jar cloud.lunarsky.store.ClientLimitsTest
RUN java --add-modules jdk.httpserver,java.net.http -cp /out:/tmp/hash4j.jar cloud.lunarsky.store.ClusterNodeTest
RUN java -cp /out:/tmp/hash4j.jar cloud.lunarsky.store.CliTest

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -g 10001 store && adduser -D -u 10001 -G store store && mkdir /data && chown store:store /data
COPY --from=build /out /app
COPY --from=build /tmp/postgresql.jar /app/postgresql.jar
COPY --from=build /tmp/hash4j.jar /app/hash4j.jar
COPY lib/LICENSE.hash4j /app/LICENSE.hash4j
COPY scripts/objectstore /usr/local/bin/objectstore
RUN chmod 755 /usr/local/bin/objectstore
USER store
EXPOSE 9000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-Dsun.net.httpserver.maxReqTime=30", "-Dsun.net.httpserver.maxRspTime=60", "-Dsun.net.httpserver.maxReqHeaders=64", "--add-modules", "jdk.httpserver,java.net.http", "-cp", "/app:/app/postgresql.jar:/app/hash4j.jar", "cloud.lunarsky.store.Main"]
