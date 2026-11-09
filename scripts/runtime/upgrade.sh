#!/usr/bin/env bash
set -euo pipefail
ARCHIVE="${1:-}"
ROOT="${2:-${DATAASK_HOME:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)}}"
if [ -z "$ARCHIVE" ] || [ ! -f "$ARCHIVE" ] || [ ! -f "$ROOT/conf/application.yml" ]; then
  echo '用法: bash bin/upgrade.sh /path/to/dataask-版本.tar.gz [现有安装目录]' >&2; exit 1
fi
ROOT="$(cd "$ROOT" && pwd -P)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
tar -tzf "$ARCHIVE" > "$TMP/entries"
if grep -Eq '(^/|(^|/)\.\.(/|$))' "$TMP/entries" || grep -Ev '^dataask-0\.1\.0(/|$)' "$TMP/entries" >/dev/null; then
  echo '升级包路径不正确' >&2; exit 1
fi
tar -tvzf "$ARCHIVE" > "$TMP/details"
if grep -Eq '^[lh]' "$TMP/details"; then echo '升级包不能包含链接' >&2; exit 1; fi
tar --no-same-owner --no-same-permissions -xzf "$ARCHIVE" -C "$TMP"
NEW="$TMP/dataask-0.1.0"
for item in lib/dataask.jar bin/runtime.sh bin/start.sh bin/stop.sh bin/status.sh bin/upgrade.sh; do
  [ -s "$NEW/$item" ] || { echo "升级包缺少 $item" >&2; exit 1; }
done
unzip -p "$NEW/lib/dataask.jar" BOOT-INF/classes/static/build-info.json > "$TMP/build-info.json"
grep -F 'table-column-selector-v1' "$TMP/build-info.json" >/dev/null || { echo '升级包不是完整新版' >&2; exit 1; }
export DATAASK_HOME="$ROOT"
source "$NEW/bin/runtime.sh"
control_lock
BACKUP="$ROOT/backups/$(date '+%Y%m%d_%H%M%S')_$$"
mkdir -p "$BACKUP/bin" "$ROOT/lib" "$ROOT/bin"
cp "$ROOT/lib/dataask.jar" "$BACKUP/dataask.jar"
cp "$ROOT/bin/"*.sh "$BACKUP/bin/"
# Use the NEW stop implementation even if the installed stop script removed its PID prematurely.
bash "$NEW/bin/stop.sh"
cp "$NEW/lib/dataask.jar" "$JAR.next"; mv -f "$JAR.next" "$JAR"
for script in runtime.sh start.sh stop.sh status.sh upgrade.sh; do
  cp "$NEW/bin/$script" "$ROOT/bin/$script.next"
  chmod +x "$ROOT/bin/$script.next"; mv -f "$ROOT/bin/$script.next" "$ROOT/bin/$script"
done
if ! bash "$ROOT/bin/start.sh"; then
  echo '新版未通过启动校验，正在恢复原程序（不回滚或删除 MySQL 数据）' >&2
  bash "$ROOT/bin/stop.sh" || { echo '新进程未退出，停止自动回退，保留备份' >&2; exit 1; }
  cp "$BACKUP/dataask.jar" "$JAR.next"; mv -f "$JAR.next" "$JAR"
  for script in "$BACKUP/bin/"*.sh; do
    name="$(basename "$script")"; cp "$script" "$ROOT/bin/$name.next"; mv -f "$ROOT/bin/$name.next" "$ROOT/bin/$name"
  done
  bash "$ROOT/bin/start.sh" || true
  echo "升级失败，原程序备份: $BACKUP" >&2; exit 1
fi
cp "$TMP/build-info.json" "$ROOT/build-info.json"
echo '升级成功：运行进程及构建版本已核对。conf/application.yml、用户和 MySQL 配置均保留。'
echo "原程序备份: $BACKUP"
