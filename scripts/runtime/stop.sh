#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/runtime.sh"
control_lock
PID="$(installation_pid)"
if [ -z "$PID" ]; then rm -f "$PID_FILE"; echo 'DataAsk 未运行'; exit 0; fi
kill -TERM "$PID"
for ((i=0;i<${DATAASK_STOP_TIMEOUT:-40};i++)); do
  if ! is_app_pid "$PID"; then
    rm -f "$PID_FILE"; echo "DataAsk 已停止 PID=$PID"; exit 0
  fi
  sleep 1
done
fail "进程 PID=$PID 尚未退出，保留 PID 文件并拒绝后续覆盖；请检查日志"; exit 1
