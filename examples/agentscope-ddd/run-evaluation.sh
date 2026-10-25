#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ "$#" -lt 1 || "$#" -gt 2 || "$1" != deepseek || ( "$#" -eq 2 && "$2" != prepare ) ]]; then
  echo 'usage: ./run-evaluation.sh deepseek [prepare]' >&2
  exit 2
fi
../../mvnw -q -f pom.xml -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
exec java -cp "target/classes:$(cat target/classpath.txt)" dev.agentharness.demo.evaluation.EvaluationMain "$@"
