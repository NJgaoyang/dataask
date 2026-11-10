#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
java -version 2>&1 | head -1 | grep -q '"21\.' || { echo "需要 Java 21"; exit 1; }
command -v mvn >/dev/null || { echo "需要 Maven 3.9+"; exit 1; }
command -v npm >/dev/null || { echo "需要 Node.js 22 和 npm"; exit 1; }
BUILD_SHA="$(git rev-parse --short=8 HEAD 2>/dev/null || echo unknown)"
BUILT_AT="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
(cd web && if [ -f package-lock.json ]; then npm ci; else npm install --no-audit --no-fund; fi && DATAASK_BUILD_SHA="$BUILD_SHA" npm run build)
mkdir -p src/main/resources/static
rm -rf src/main/resources/static/*
cp -R web/dist/. src/main/resources/static/
printf '{"buildSha":"%s","builtAt":"%s","uiLayout":"desktop-auto-join-v7","modelManagement":"web"}\n' "$BUILD_SHA" "$BUILT_AT" > src/main/resources/static/build-info.json
mvn -B clean package
OUT="$ROOT/release/dataask-0.1.0"
rm -rf "$OUT"
mkdir -p "$OUT/bin" "$OUT/conf" "$OUT/lib" "$OUT/logs"
cp target/dataask-0.1.0-SNAPSHOT.jar "$OUT/lib/dataask.jar"
cp scripts/runtime/start.sh scripts/runtime/stop.sh scripts/runtime/upgrade.sh "$OUT/bin/"
cp scripts/runtime/application.yml "$OUT/conf/application.yml"
chmod +x "$OUT/bin/"*.sh
tar -czf "$ROOT/release/dataask-0.1.0.tar.gz" -C "$ROOT/release" dataask-0.1.0
echo "完成: release/dataask-0.1.0.tar.gz (build: $BUILD_SHA)"
echo "部署后在浏览器打开 /build-info.json，可确认服务器实际运行的新版本"
