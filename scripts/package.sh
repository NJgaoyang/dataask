#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
for TOOL in java mvn node npm tar unzip; do command -v "$TOOL" >/dev/null || { echo "缺少构建工具: $TOOL"; exit 1; }; done
mvn -B clean package
JAR="$ROOT/target/dataask-0.1.0-SNAPSHOT.jar"
INFO="$(unzip -p "$JAR" BOOT-INF/classes/static/build-info.json)"
echo "$INFO" | grep -F 'table-column-selector-v1' >/dev/null || { echo "JAR 中缺少新版前端，拒绝发布"; exit 1; }
BUILD_SHA="$(printf '%s' "$INFO" | sed -n 's/.*"buildSha":"\([^" ]*\)".*/\1/p')"
[ -n "$BUILD_SHA" ] || { echo "缺少构建版本"; exit 1; }
OUT="$ROOT/release/dataask-0.1.0"
rm -rf "$OUT"
mkdir -p "$OUT/bin" "$OUT/conf" "$OUT/lib" "$OUT/logs"
cp "$JAR" "$OUT/lib/dataask.jar"
cp scripts/runtime/*.sh "$OUT/bin/"
cp scripts/runtime/application.yml "$OUT/conf/application.yml"
printf '%s\n' "$INFO" > "$OUT/build-info.json"
chmod +x "$OUT/bin/"*.sh
ARCHIVE="$ROOT/release/dataask-0.1.0-$BUILD_SHA.tar.gz"
tar -czf "$ARCHIVE" -C "$ROOT/release" dataask-0.1.0
cp "$ARCHIVE" "$ROOT/release/dataask-0.1.0.tar.gz"
(cd "$ROOT/release" && sha256sum "$(basename "$ARCHIVE")" > "$(basename "$ARCHIVE").sha256")
echo "完成: $ARCHIVE"
echo "构建信息: $INFO"
echo "请升级实际运行目录，并访问 /api/build-info 核对 buildSha 和启动 PID。"
