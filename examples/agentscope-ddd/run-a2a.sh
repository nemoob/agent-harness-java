#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# macOS 自动选 Java 17；其他平台沿用 JAVA_HOME 或 PATH。
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi
../../mvnw -q -f pom.xml -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
# JDK 17 的请求头与连接限制需在 HttpServer 首次加载前设置。
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" \
  -Dsun.net.httpserver.maxReqTime=10 -Dsun.net.httpserver.maxRspTime=200 \
  -Dsun.net.httpserver.timerMillis=1000 -Djdk.httpserver.maxConnections=8 \
  -Dsun.net.httpserver.maxReqHeaders=32 \
  -cp "target/classes:$(cat target/classpath.txt)" dev.agentharness.demo.bootstrap.A2aMain "$@"
