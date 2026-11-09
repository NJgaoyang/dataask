#!/usr/bin/env bash
# Shared Linux runtime checks. Only this installation's absolute JAR path is managed.
ROOT="$(cd "${DATAASK_HOME:-$(dirname "${BASH_SOURCE[0]}")/..}" && pwd -P)"
PID_FILE="$ROOT/logs/dataask.pid"
JAR="$ROOT/lib/dataask.jar"
mkdir -p "$ROOT/logs"
fail() { echo "错误: $*" >&2; return 1; }
control_lock() {
  if [ "${DATAASK_CONTROL_LOCKED:-0}" != 1 ]; then
    command -v flock >/dev/null || { fail '需要 flock（util-linux）'; return 1; }
    exec 9>"$ROOT/logs/control.lock"
    flock -n 9 || { fail '另一个启动、停止或升级操作正在执行'; return 1; }
    export DATAASK_CONTROL_LOCKED=1
  fi
}
is_app_pid() {
  local pid="$1" i
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  [[ "$(ps -p "$pid" -o stat= 2>/dev/null)" != Z* ]] || return 1
  local -a args=()
  [ -r "/proc/$pid/cmdline" ] || return 1
  mapfile -d '' -t args < "/proc/$pid/cmdline" 2>/dev/null || return 1
  for ((i=0; i+1<${#args[@]}; i++)); do
    if [[ "${args[i]}" == '-jar' && "${args[i+1]}" == "$JAR" ]]; then return 0; fi
  done
  return 1
}
installation_pid() {
  local value='' file pid
  if [ -f "$PID_FILE" ]; then
    value="$(cat "$PID_FILE")"
    if is_app_pid "$value"; then printf '%s' "$value"; return 0; fi
    if [[ "$value" =~ ^[1-9][0-9]*$ ]] && kill -0 "$value" 2>/dev/null &&
        [[ "$(ps -p "$value" -o stat= 2>/dev/null)" != Z* ]]; then
      fail "PID 文件指向其他进程 $value，拒绝操作"; return 1
    fi
  fi
  local -a found=()
  for file in /proc/[0-9]*/cmdline; do
    pid="${file#/proc/}"; pid="${pid%/cmdline}"
    if is_app_pid "$pid"; then found+=("$pid"); fi
  done
  if [ "${#found[@]}" -gt 1 ]; then fail "同一安装目录存在多个进程: ${found[*]}"; return 1; fi
  if [ "${#found[@]}" -eq 1 ]; then printf '%s' "${found[0]}"; fi
}
service_port() {
  local value="${DATAASK_PORT:-}"
  if [ -z "$value" ]; then
    value="$(awk '/^server:[[:space:]]*$/ {inside=1;next} inside && /^[^[:space:]#]/ {inside=0} inside && /^[[:space:]]+port:/ {sub(/^[[:space:]]+port:[[:space:]]*/, "");sub(/[[:space:]]+#.*/, "");print;exit}' "$ROOT/conf/application.yml")"
    value="$(printf '%s' "$value" | tr -d "\"' \r")"
    if [[ "$value" =~ ^\$\{DATAASK_PORT:([0-9]+)\}$ ]]; then value="${BASH_REMATCH[1]}"; fi
    value="${value:-8087}"
  fi
  [[ "$value" =~ ^[1-9][0-9]{0,4}$ ]] && [ "$value" -le 65535 ] || { fail '端口格式不支持，请设置 DATAASK_PORT 或 server.port 数字'; return 1; }
  printf '%s' "$value"
}
jar_build_sha() {
  unzip -p "$JAR" BOOT-INF/classes/static/build-info.json | sed -n 's/.*"buildSha":"\([^" ]*\)".*/\1/p'
}
