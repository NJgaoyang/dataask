#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$ROOT/logs"
PID_FILE="$ROOT/logs/dataask.pid"

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "DataAsk 已运行 PID=$(cat "$PID_FILE")"
  exit 0
fi

command -v java >/dev/null || { echo "未找到 java，请安装 Java 21"; exit 1; }
if ! java -version 2>&1 | head -1 | grep -Eq '"21([.+-]|")'; then
  echo "DataAsk 需要 Java 21；当前环境："
  java -version 2>&1 | head -1
  exit 1
fi

if [ ! -f "$ROOT/conf/application.yml" ]; then
  echo "未找到 $ROOT/conf/application.yml，请先填写 MySQL、StarRocks 和 AI 配置"
  exit 1
fi

# First-run convenience: generate an installation-specific persistent signing secret
# only when neither an environment variable nor an inline YAML secret is configured.
CFG_SECRET="$(awk -F: '/^[[:space:]]*jwt-secret:[[:space:]]*/ { gsub(/[[:space:]"\047]/, "", $2); print $2; exit }' "$ROOT/conf/application.yml")"
if [ -z "${DATAASK_JWT_SECRET:-}" ] && [ -z "$CFG_SECRET" ]; then
  SECRET_FILE="$ROOT/conf/.dataask-jwt-secret"
  if [ ! -s "$SECRET_FILE" ]; then
    command -v openssl >/dev/null || {
      echo "JWT 密钥未配置且 openssl 不可用。请将至少32字节的随机字符串配置到 conf/application.yml 的 dataask.jwt-secret"
      exit 1
    }
    (umask 077; openssl rand -hex 32 > "$SECRET_FILE")
    echo "已生成持久化 JWT 密钥：conf/.dataask-jwt-secret（请备份此文件）"
  fi
  chmod 600 "$SECRET_FILE"
  export DATAASK_JWT_SECRET="$(cat "$SECRET_FILE")"
fi

if [ -n "${DATAASK_JWT_SECRET:-}" ] && [ "${#DATAASK_JWT_SECRET}" -lt 32 ]; then
  echo "DATAASK_JWT_SECRET 至少需要32字节，请重新配置"
  exit 1
fi

nohup java ${JAVA_OPTS:--Xms256m -Xmx1024m} -Duser.timezone=Asia/Shanghai \
  -jar "$ROOT/lib/dataask.jar" --spring.config.additional-location="file:$ROOT/conf/" \
  >> "$ROOT/logs/dataask.log" 2>&1 &
echo $! > "$PID_FILE"
echo "DataAsk 启动请求已提交 PID=$(cat "$PID_FILE")。如未能访问，请检查 logs/dataask.log 最末尾的 Caused by"
