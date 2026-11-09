#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/runtime.sh"
control_lock
for tool in java curl unzip; do command -v "$tool" >/dev/null || { fail "缺少运行工具 $tool"; exit 1; }; done
VERSION="$(java -version 2>&1)"
[[ "$VERSION" =~ \"21[.\"+-] ]] || { fail 'DataAsk 需要 Java 21'; exit 1; }
[ -f "$ROOT/conf/application.yml" ] && [ -s "$JAR" ] || { fail '缺少 conf/application.yml 或 lib/dataask.jar'; exit 1; }
PORT="$(service_port)"
BASE="http://127.0.0.1:$PORT"
EXPECTED="$(jar_build_sha)"
[ -n "$EXPECTED" ] || { fail 'JAR 未包含前端版本信息，请使用 bash package.sh 或 mvn clean package 重新打包'; exit 1; }
PID="$(installation_pid)"
if [ -n "$PID" ]; then
  INFO="$(curl -fsS --max-time 3 "$BASE/api/build-info" 2>/dev/null || true)"
  if [[ ( "$INFO" == *'"pid":'"$PID"','* || "$INFO" == *'"pid":'"$PID"'}'* ) && "$INFO" == *'"buildSha":"'"$EXPECTED"'"'* ]]; then
    echo "DataAsk 已运行 PID=$PID，版本=$EXPECTED，端口=$PORT"; exit 0
  fi
  fail "旧进程 PID=$PID 仍在运行或版本不一致，请使用 bin/upgrade.sh，不能只覆盖 JAR 后再次 start"; exit 1
fi
if command -v ss >/dev/null && [ -n "$(ss -ltnH "sport = :$PORT" 2>/dev/null)" ]; then
  fail "端口 $PORT 已被占用，新版本尚未启动；请核对正在运行的安装目录"; exit 1
fi
# Import an old installation's key exactly once. New installations store their generated key in MySQL.
if [ -z "${DATAASK_JWT_SECRET:-}" ] && [ -s "$ROOT/conf/.dataask-jwt-secret" ]; then
  export DATAASK_JWT_SECRET="$(cat "$ROOT/conf/.dataask-jwt-secret")"
fi
read -r -a JVM_OPTIONS <<< "${JAVA_OPTS:--Xms128m -Xmx1024m}"
nohup java "${JVM_OPTIONS[@]}" -Duser.timezone=Asia/Shanghai -jar "$JAR" \
  --spring.config.additional-location="file:$ROOT/conf/" --server.port="$PORT" \
  >> "$ROOT/logs/dataask.log" 2>&1 9>&- &
PID=$!
printf '%s\n' "$PID" > "$PID_FILE"
for ((i=0;i<${DATAASK_START_TIMEOUT:-90};i++)); do
  if ! kill -0 "$PID" 2>/dev/null; then
    rm -f "$PID_FILE"; tail -n 50 "$ROOT/logs/dataask.log" >&2
    fail '新进程启动失败，不能视为发布成功'; exit 1
  fi
  INFO="$(curl -fsS --max-time 2 "$BASE/api/build-info" 2>/dev/null || true)"
  if [[ ( "$INFO" == *'"pid":'"$PID"','* || "$INFO" == *'"pid":'"$PID"'}'* ) && "$INFO" == *'"buildSha":"'"$EXPECTED"'"'* ]]; then
    echo "DataAsk 启动成功 PID=$PID，版本=$EXPECTED，端口=$PORT"
    echo "$INFO"; exit 0
  fi
  sleep 1
done
fail "进程 PID=$PID 仍未通过版本和健康校验，请检查 $ROOT/logs/dataask.log"; exit 1
