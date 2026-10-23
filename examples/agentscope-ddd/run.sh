#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# 复用仓库 Maven Wrapper，不要求读者额外安装 Maven。
../../mvnw -q -f pom.xml -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
# Java 进程直接接管标准输入，保留多轮交互与退出码。
if [ "$#" -eq 0 ]; then set -- deepseek; fi
exec java -cp "target/classes:$(cat target/classpath.txt)" dev.agentharness.demo.bootstrap.Main "$@"
