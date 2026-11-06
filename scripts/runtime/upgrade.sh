#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARCHIVE="${1:-}"
if [ -z "$ARCHIVE" ] || [ ! -f "$ARCHIVE" ]; then
  echo "用法: bash bin/upgrade.sh /path/to/dataask-0.1.0.tar.gz"
  exit 1
fi
if [ ! -f "$ROOT/conf/application.yml" ]; then
  echo "未找到当前部署的 conf/application.yml，拒绝升级以保护配置"
  exit 1
fi
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
if tar -tzf "$ARCHIVE" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
  echo "升级包包含不安全的路径"
  exit 1
fi
tar -xzf "$ARCHIVE" -C "$TMP"
NEW_JAR="$TMP/dataask-0.1.0/lib/dataask.jar"
if [ ! -s "$NEW_JAR" ]; then
  echo "升级包中没有 dataask.jar"
  exit 1
fi
echo "准备升级 DataAsk，仅替换 JAR 和脚本，保留 conf/application.yml、密钥和 MySQL"
bash "$ROOT/bin/stop.sh"
sleep 2
PID_FILE="$ROOT/logs/dataask.pid"
if [ -f "$PID_FILE" ]; then
  PID="$(cat "$PID_FILE")"
  if kill -0 "$PID" 2>/dev/null; then
    echo "旧进程仍在运行，拒绝替换程序，请先检查 PID=$PID"
    exit 1
  fi
fi
STAMP="$(date '+%Y%m%d_%H%M%S')"
mkdir -p "$ROOT/lib" "$ROOT/logs"
if [ -f "$ROOT/lib/dataask.jar" ]; then
  cp "$ROOT/lib/dataask.jar" "$ROOT/lib/dataask.jar.$STAMP.bak"
fi
cp "$NEW_JAR" "$ROOT/lib/dataask.jar.next"
mv -f "$ROOT/lib/dataask.jar.next" "$ROOT/lib/dataask.jar"
for SCRIPT in start.sh stop.sh upgrade.sh; do
  if [ -f "$TMP/dataask-0.1.0/bin/$SCRIPT" ]; then
    cp "$TMP/dataask-0.1.0/bin/$SCRIPT" "$ROOT/bin/$SCRIPT"
    chmod +x "$ROOT/bin/$SCRIPT"
  fi
done
echo "已替换 JAR 和脚本，现有 conf/application.yml 没有被覆盖"
bash "$ROOT/bin/start.sh"
echo "请检查 logs/dataask.log 确认启动完成，并访问 /build-info.json 核对版本"
