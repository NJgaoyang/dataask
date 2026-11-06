#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="$ROOT/logs/dataask.pid"
if [ ! -f "$PID_FILE" ]; then echo "DataAsk 未运行"; exit 0; fi
PID="$(cat "$PID_FILE")"
if kill -0 "$PID" 2>/dev/null; then
  if ps -p "$PID" -o args= | grep -q 'dataask.jar'; then kill "$PID"; echo "正在停止 PID=$PID";
  else echo "PID 不属于 DataAsk，拒绝操作"; exit 1; fi
fi
rm -f "$PID_FILE"
