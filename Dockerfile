FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src
COPY src ./src
COPY test ./test
RUN mkdir /out && javac --release 21 --add-modules jdk.httpserver,java.net.http -d /out src/cloud/lunarsky/store/*.java test/cloud/lunarsky/store/*.java
RUN java --add-modules jdk.httpserver -cp /out cloud.lunarsky.store.StoreTest
RUN java --add-modules jdk.httpserver,java.net.http -cp /out cloud.lunarsky.store.HttpTest

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -g 10001 store && adduser -D -u 10001 -G store store && mkdir /data && chown store:store /data
COPY --from=build /out /app
USER store
EXPOSE 9000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-Dsun.net.httpserver.maxReqTime=30", "-Dsun.net.httpserver.maxRspTime=60", "-Dsun.net.httpserver.maxReqHeaders=64", "--add-modules", "jdk.httpserver", "-cp", "/app", "cloud.lunarsky.store.Main"]
