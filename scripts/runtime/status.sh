#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/runtime.sh"
PID="$(installation_pid)"
if [ -z "$PID" ]; then echo 'DataAsk 未运行'; exit 1; fi
PORT="$(service_port)"
echo "安装目录=$ROOT PID=$PID 端口=$PORT"
echo "磁盘 JAR 版本=$(jar_build_sha)"
curl -fsS --max-time 3 "http://127.0.0.1:$PORT/api/build-info"
echo
