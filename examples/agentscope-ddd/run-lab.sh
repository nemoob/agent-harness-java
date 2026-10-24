#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# macOS 选用 Java 17；其他平台沿用已配置的 JDK。
if [[ -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
# 教学入口仅依赖 JDK，避免为了观察 Loop 下载模型依赖或配置密钥。
mkdir -p target/lab-classes
javac --release 17 -encoding UTF-8 -d target/lab-classes \
  src/main/java/dev/agentharness/demo/domain/DateValidation.java \
  src/main/java/dev/agentharness/demo/lab/*.java
exec java -cp target/lab-classes dev.agentharness.demo.lab.LabMain "$@"
