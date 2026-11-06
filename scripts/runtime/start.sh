#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$ROOT/logs"
PID_FILE="$ROOT/logs/dataask.pid"
if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "DataAsk 已运行 PID=$(cat "$PID_FILE")"; exit 0
fi
nohup java ${JAVA_OPTS:--Xms256m -Xmx1024m} -Duser.timezone=Asia/Shanghai \
  -jar "$ROOT/lib/dataask.jar" --spring.config.additional-location="file:$ROOT/conf/" \
  >>"$ROOT/logs/dataask.log" 2>&1 &
echo $! > "$PID_FILE"
echo "已启动 PID=$(cat "$PID_FILE")，请检查 logs/dataask.log"
