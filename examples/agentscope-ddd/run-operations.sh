#!/bin/sh
set -eu

# 只在脚本所在的固定示例目录构建和运行。
cd "$(dirname "$0")"
case "${1:-experiment}" in
  build|offline|experiment) action="${1:-experiment}" ;;
  *) echo "usage: ./run-operations.sh [build|offline|experiment]" >&2; exit 2 ;;
esac
if [ "$#" -gt 1 ]; then
  echo "unexpected argument" >&2
  exit 2
fi

out=target/operations-bundle
private="$out/private"
jar="$out/operations.jar"
log="$out/server.log"
evidence=docs/evidence/operations-local.json
server_mode=deepseek
if [ "$action" = offline ]; then
  server_mode=offline
  evidence=docs/evidence/operations-offline.json
  log="$out/server-offline.log"
fi
case "$(uname -s)" in Darwin|Linux) ;; *) echo "POSIX permissions required" >&2; exit 2 ;; esac
for path in target "$out" "$private" "$jar" "$log" "$out/manifest.mf" "$out/manifest.sha256" "$out/lib" "$evidence"; do
  if [ -L "$path" ]; then
    echo "unsafe symbolic link: $path" >&2
    exit 2
  fi
done
mkdir -p "$out" docs/evidence
chmod 700 "$out"
if [ -e "$private/connection" ] || [ -L "$private/connection" ]; then
  echo "stale connection file; inspect before retry" >&2
  exit 2
fi
../../mvnw -q -o -f pom.xml -DskipTests package
rm -rf "$out/lib"
mkdir -p "$out/lib"
../../mvnw -q -o -f pom.xml dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory="$out/lib"
class_path=
for dependency in "$out"/lib/*.jar; do
  [ -f "$dependency" ] || { echo "runtime dependencies missing" >&2; exit 1; }
  class_path="${class_path:+$class_path }lib/$(basename "$dependency")"
done
printf 'Main-Class: dev.agentharness.demo.operations.OperationsMain\n' > "$out/manifest.mf"
# JAR manifest 的物理行须折叠；续行首空格由清单解析器剥离。
printf 'Class-Path: %s\n' "$class_path" | fold -b -w 69 | sed '2,$s/^/ /' >> "$out/manifest.mf"
jar --create --file "$jar" --manifest "$out/manifest.mf" -C target/classes .
hash="$(shasum -a 256 "$jar" | awk '{print $1}')"
shasum -a 256 "$jar" "$out"/lib/*.jar config/application.properties config/operations-prompt.txt > "$out/manifest.sha256"
manifest_hash="$(shasum -a 256 "$out/manifest.sha256" | awk '{print $1}')"
if [ "$action" = build ]; then
  printf 'artifact=%s sha256=%s manifest_sha256=%s\n' "$jar" "$hash" "$manifest_hash"
  exit 0
fi

# 信号只送给本脚本启动且命令行仍与固定 JAR 匹配的子进程。
pid=
pid_command=
pid_start=
pid_parent=
identity_ok() {
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null || return 1
  command_line="$(ps -ww -p "$pid" -o command= 2>/dev/null || true)"
  start_line="$(ps -p "$pid" -o lstart= 2>/dev/null || true)"
  parent_line="$(ps -p "$pid" -o ppid= 2>/dev/null | tr -d ' ' || true)"
  case "$command_line" in *"-jar $jar server $server_mode") ;; *) return 1 ;; esac
  [ "$parent_line" = "$$" ] || return 1
  if [ -n "$pid_command" ]; then
    [ "$command_line" = "$pid_command" ] && [ "$start_line" = "$pid_start" ] && [ "$parent_line" = "$pid_parent" ] || return 1
  fi
  return 0
}
cleanup() {
  if identity_ok; then
    kill -TERM "$pid"
    limit=0
    while kill -0 "$pid" 2>/dev/null && [ "$limit" -lt 80 ]; do
      limit=$((limit + 1))
      sleep 0.1
    done
    if identity_ok; then
      kill -KILL "$pid"
    fi
  fi
  if [ -n "$pid" ]; then
    wait "$pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT HUP INT TERM

: > "$log"
java -Djdk.httpserver.maxConnections=16 \
  -Dsun.net.httpserver.maxReqHeaders=16 \
  -Dsun.net.httpserver.maxReqHeaderSize=4096 \
  -Dsun.net.httpserver.maxReqTime=5 \
  -Dsun.net.httpserver.maxRspTime=200 \
  -Dsun.net.httpserver.timerMillis=1000 \
  -Dsun.net.httpserver.idleInterval=5 \
  -Dsun.net.httpserver.maxIdleConnections=4 \
  -jar "$jar" server "$server_mode" > "$log" 2>&1 &
pid=$!
limit=0
while [ ! -f "$private/connection" ] && [ "$limit" -lt 80 ]; do
  if ! identity_ok; then
    echo "server exited before readiness" >&2
    exit 1
  fi
  limit=$((limit + 1))
  sleep 0.1
done
if [ ! -f "$private/connection" ] || ! identity_ok; then
  echo "server readiness timed out" >&2
  exit 1
fi
pid_command="$(ps -ww -p "$pid" -o command=)"
pid_start="$(ps -p "$pid" -o lstart=)"
pid_parent="$(ps -p "$pid" -o ppid= | tr -d ' ')"
if ! identity_ok; then
  echo "server process fingerprint changed" >&2
  exit 1
fi
client_output="$(java -jar "$jar" client 2024-02-29)"
request_id="$(printf '%s\n' "$client_output" | sed -n 's/^CLIENT requestId=\(r-[0-9a-f]\{16\}\) date=2024-02-29 valid=true status=200$/\1/p')"
if [ -z "$request_id" ] || ! printf '%s\n' "$client_output" | grep -q '^CLIENT reject=400 reason=request_id$' \
    || ! printf '%s\n' "$client_output" | grep -q '^CLIENT reject=401 reason=auth$' \
    || ! printf '%s\n' "$client_output" | grep -q '^CLIENT reject=401 reason=auth_variants$' \
    || ! printf '%s\n' "$client_output" | grep -q '^CLIENT reject=400 reason=route$' \
    || ! printf '%s\n' "$client_output" | grep -q '^CLIENT reject=400 reason=date_format$' \
    || ! printf '%s\n' "$client_output" | grep -q '^CLIENT slow_body_closed=true$'; then
  echo "client checks failed" >&2
  exit 1
fi
if ! identity_ok; then
  echo "server identity changed before SIGTERM" >&2
  exit 1
fi
start_time="$(date +%s)"
kill -TERM "$pid"
limit=0
while kill -0 "$pid" 2>/dev/null && [ "$limit" -lt 80 ]; do
  limit=$((limit + 1))
  sleep 0.1
done
if kill -0 "$pid" 2>/dev/null; then
  echo "SIGTERM exit exceeded 8 seconds" >&2
  exit 1
fi
set +e
wait "$pid"
exit_status=$?
set -e
end_time="$(date +%s)"
pid_saved=$pid
pid=
if [ "$exit_status" -ne 143 ] && [ "$exit_status" -ne 0 ]; then
  echo "unexpected JVM exit status: $exit_status" >&2
  exit 1
fi
for expected in "START host=127.0.0.1" "REJECT reason=auth" "RECEIVE requestId=$request_id" "RULE requestId=$request_id valid=true" "RESPONSE requestId=$request_id status=200" "REJECT reason=route_or_request_id" "REJECT requestId=$request_id reason=date_format" "STOP phase=stop_accepting requestId=$request_id" "STOP phase=workers_wait" "STOP phase=connection_removed" "STOP phase=done"; do
  if ! grep -F -q "$expected" "$log"; then
    echo "missing server phase: $expected" >&2
    exit 1
  fi
done
agent_tool_called=false
if [ "$server_mode" = deepseek ]; then
  for expected in "TOOL requestId=$request_id date=2024-02-29 valid=true" "AGENT requestId=$request_id outcome=tool_called"; do
    if ! grep -F -q "$expected" "$log"; then
      echo "missing agent phase: $expected" >&2
      exit 1
    fi
  done
  agent_tool_called=true
fi
auth_line="$(grep -n '^REJECT reason=auth$' "$log" | head -1 | cut -d: -f1)"
receive_line="$(grep -n "^RECEIVE requestId=$request_id$" "$log" | head -1 | cut -d: -f1)"
if [ "$auth_line" -ge "$receive_line" ]; then
  echo "authentication rejection reached agent path" >&2
  exit 1
fi
if [ "$(grep -c '^REJECT reason=auth$' "$log")" -ne 4 ]; then
  echo "authentication rejection count mismatch" >&2
  exit 1
fi
if [ "$(grep -c "^RECEIVE requestId=$request_id$" "$log")" -ne 2 ]; then
  echo "rejected or slow request reached execution" >&2
  exit 1
fi
model="$(sed -n "s/^START host=127\\.0\\.0\\.1 port=[0-9][0-9]* provider=$server_mode model=\\([A-Za-z0-9_.:/-]*\\)$/\\1/p" "$log" | head -1)"
if [ -z "$model" ]; then
  echo "non-sensitive model identity missing" >&2
  exit 1
fi
if [ -e "$private/connection" ] || kill -0 "$pid_saved" 2>/dev/null; then
  echo "connection or child remains" >&2
  exit 1
fi
elapsed=$((end_time - start_time))
printf '{"artifact":"target/operations-bundle/operations.jar","sha256":"%s","bundleManifest":"target/operations-bundle/manifest.sha256","bundleManifestSha256":"%s","provider":"%s","model":"%s","argv":["java","-Djdk.httpserver.maxConnections=16","-Dsun.net.httpserver.maxReqHeaders=16","-Dsun.net.httpserver.maxReqHeaderSize=4096","-Dsun.net.httpserver.maxReqTime=5","-Dsun.net.httpserver.maxRspTime=200","-Dsun.net.httpserver.timerMillis=1000","-Dsun.net.httpserver.idleInterval=5","-Dsun.net.httpserver.maxIdleConnections=4","-jar","target/operations-bundle/operations.jar","server","%s"],"pid":%s,"parentPid":%s,"processStart":"%s","requestId":"%s","request":{"status":200,"date":"2024-02-29","valid":true,"agentToolCalled":%s},"authenticationRejectionStatus":401,"authenticationRejectedBeforeReceive":true,"rejectionStatus":400,"sigtermTarget":%s,"shutdownPhases":["stop_accepting","workers_wait","connection_removed","done"],"exitStatus":%s,"elapsedSeconds":%s,"deadlineSeconds":8,"connectionRemoved":true,"childRemaining":false,"serverLog":"%s"}\n' \
  "$hash" "$manifest_hash" "$server_mode" "$model" "$server_mode" "$pid_saved" "$pid_parent" "$pid_start" "$request_id" "$agent_tool_called" "$pid_saved" "$exit_status" "$elapsed" "$log" > "$evidence"
printf 'artifact_sha256=%s pid=%s requestId=%s reject=400 SIGTERM=%s exit=%s elapsed=%ss connection_removed=true child_remaining=false\n' \
  "$hash" "$pid_saved" "$request_id" "$pid_saved" "$exit_status" "$elapsed"
