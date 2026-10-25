#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# 只接受现有供应商名称，不将模型凭据放在参数或脚本中。
if [ "$#" -gt 1 ]; then echo "只接受 deepseek/ollama 参数" >&2; exit 2; fi
provider="${1:-deepseek}"
case "$provider" in deepseek|ollama) ;; *) echo "只接受 deepseek/ollama 参数" >&2; exit 2 ;; esac
# 复用工程 Maven Wrapper 编译固定教学入口。
../../mvnw -q -f pom.xml -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
exec java -cp "target/classes:$(cat target/classpath.txt)" dev.agentharness.demo.investigation.InvestigationMain "$provider"
