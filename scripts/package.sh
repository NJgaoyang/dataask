#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

APP_VERSION="0.1.0"
APP_DIR="dataask-${APP_VERSION}"
RELEASE_DIR="$ROOT/release"
OUT="$RELEASE_DIR/$APP_DIR"
LATEST_ARCHIVE="$RELEASE_DIR/${APP_DIR}.tar.gz"

fail() {
  echo "打包失败: $*" >&2
  exit 1
}

echo "===== DataAsk 打包 ====="

command -v java >/dev/null 2>&1 || fail "未找到 Java 21"
JAVA_LINE="$(java -version 2>&1 | head -1)"
echo "Java: $JAVA_LINE"
echo "$JAVA_LINE" | grep -Eq '"21([.+-]|")' || fail "需要 Java 21"

command -v mvn >/dev/null 2>&1 || fail "未找到 Maven"
MAVEN_LINE="$(mvn -version 2>/dev/null | head -1)"
echo "Maven: $MAVEN_LINE"

command -v node >/dev/null 2>&1 || fail "未找到 Node.js 22"
NODE_VERSION="$(node -p 'process.versions.node')"
NODE_MAJOR="${NODE_VERSION%%.*}"
echo "Node: v$NODE_VERSION"
[ "$NODE_MAJOR" = "22" ] || fail "需要 Node.js 22.x，当前为 v$NODE_VERSION"

command -v npm >/dev/null 2>&1 || fail "未找到 npm"
command -v sha256sum >/dev/null 2>&1 || fail "未找到 sha256sum"
command -v jar >/dev/null 2>&1 || fail "JDK 中未找到 jar 命令"

BUILD_SHA="$(git rev-parse --short=8 HEAD 2>/dev/null || echo unknown)"
GIT_DIRTY=0
if [ -n "$(git status --porcelain 2>/dev/null)" ]; then
  GIT_DIRTY=1
  BUILD_SHA="${BUILD_SHA}-dirty"
fi
BUILT_AT="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

echo "源码版本: $BUILD_SHA"
if [ "$GIT_DIRTY" = "1" ]; then
  echo "提示: 当前工作区存在未提交改动，本次产物标记为 -dirty"
fi

echo
echo "===== 1/5 构建前端 ====="
rm -rf "$ROOT/web/dist"
(
  cd "$ROOT/web"
  if [ -f package-lock.json ]; then
    npm ci
  else
    npm install --no-audit --no-fund
  fi
  DATAASK_BUILD_SHA="$BUILD_SHA" npm run build
)

[ -s "$ROOT/web/dist/index.html" ] || fail "web/dist/index.html 未生成"
find "$ROOT/web/dist" -type f | grep -q . || fail "web/dist 为空"

FRONTEND_HASH="$(
  cd "$ROOT/web/dist"
  find . -type f -print0     | sort -z     | xargs -0 sha256sum     | sha256sum     | awk '{print $1}'
)"
echo "前端产物哈希: $FRONTEND_HASH"

echo
echo "===== 2/5 写入 Spring Boot 静态资源 ====="
rm -rf "$ROOT/src/main/resources/static"
mkdir -p "$ROOT/src/main/resources/static"
cp -R "$ROOT/web/dist/." "$ROOT/src/main/resources/static/"

cat > "$ROOT/src/main/resources/static/build-info.json" <<EOF
{"buildSha":"$BUILD_SHA","builtAt":"$BUILT_AT","frontendHash":"$FRONTEND_HASH","uiLayout":"pc-platform-v2","uiRevision":"workbuddy-smart-qa-v1","modelManagement":"web"}
EOF

echo "build-info.json:"
cat "$ROOT/src/main/resources/static/build-info.json"
echo

echo
echo "===== 3/5 构建后端 JAR ====="
if [ "${DATAASK_SKIP_TESTS:-0}" = "1" ]; then
  echo "DATAASK_SKIP_TESTS=1：跳过测试编译与执行"
  mvn -B -Dmaven.test.skip=true clean package
else
  mvn -B clean package
fi

JAR_FILE="$ROOT/target/dataask-0.1.0-SNAPSHOT.jar"
[ -s "$JAR_FILE" ] || fail "未生成 $JAR_FILE"

echo
echo "===== 4/5 校验 JAR 内前端版本 ====="
VERIFY_DIR="$(mktemp -d)"
trap 'rm -rf "$VERIFY_DIR"' EXIT
(
  cd "$VERIFY_DIR"
  jar xf "$JAR_FILE" BOOT-INF/classes/static/build-info.json BOOT-INF/classes/static/index.html
)
PACKED_INFO="$VERIFY_DIR/BOOT-INF/classes/static/build-info.json"
PACKED_INDEX="$VERIFY_DIR/BOOT-INF/classes/static/index.html"
[ -s "$PACKED_INFO" ] || fail "JAR 内缺少 build-info.json"
[ -s "$PACKED_INDEX" ] || fail "JAR 内缺少前端 index.html"

grep -F "\"buildSha\":\"$BUILD_SHA\"" "$PACKED_INFO" >/dev/null   || fail "JAR 内 buildSha 与本次源码不一致"
grep -F "\"frontendHash\":\"$FRONTEND_HASH\"" "$PACKED_INFO" >/dev/null   || fail "JAR 内 frontendHash 与本次前端产物不一致"

echo "JAR 校验通过:"
cat "$PACKED_INFO"
echo

echo
echo "===== 5/5 生成发布包 ====="
rm -rf "$OUT"
mkdir -p "$OUT/bin" "$OUT/conf" "$OUT/lib" "$OUT/logs"

cp "$JAR_FILE" "$OUT/lib/dataask.jar"
cp "$ROOT/scripts/runtime/start.sh" "$ROOT/scripts/runtime/stop.sh" "$ROOT/scripts/runtime/upgrade.sh" "$OUT/bin/"
cp "$ROOT/scripts/runtime/application.yml" "$OUT/conf/application.yml"
cp "$ROOT/src/main/resources/static/build-info.json" "$OUT/build-info.json"
chmod +x "$OUT/bin/"*.sh

mkdir -p "$RELEASE_DIR"
rm -f "$LATEST_ARCHIVE"
tar -czf "$LATEST_ARCHIVE" -C "$RELEASE_DIR" "$APP_DIR"

VERSIONED_ARCHIVE="$RELEASE_DIR/${APP_DIR}-${BUILD_SHA}.tar.gz"
rm -f "$VERSIONED_ARCHIVE"
cp "$LATEST_ARCHIVE" "$VERSIONED_ARCHIVE"

LATEST_SHA256="$(sha256sum "$LATEST_ARCHIVE" | awk '{print $1}')"
printf '%s  %s\n' "$LATEST_SHA256" "$(basename "$LATEST_ARCHIVE")" > "${LATEST_ARCHIVE}.sha256"
printf '%s  %s\n' "$LATEST_SHA256" "$(basename "$VERSIONED_ARCHIVE")" > "${VERSIONED_ARCHIVE}.sha256"

echo
echo "===== 打包完成 ====="
echo "固定包:    release/${APP_DIR}.tar.gz"
echo "版本包:    release/${APP_DIR}-${BUILD_SHA}.tar.gz"
echo "SHA256:    $LATEST_SHA256"
echo "Build SHA: $BUILD_SHA"
echo "前端哈希:  $FRONTEND_HASH"
echo
echo "建议部署版本包，避免同名旧文件混淆："
echo "  bash /opt/dataask/bin/upgrade.sh \"$VERSIONED_ARCHIVE\" /opt/dataask"
echo
echo "部署后核对："
echo "  curl -s http://127.0.0.1:8087/build-info.json"
echo "返回的 buildSha 必须为: $BUILD_SHA"
echo "返回的 frontendHash 必须为: $FRONTEND_HASH"
