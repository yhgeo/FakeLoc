#!/usr/bin/env bash
# ===========================================================================
#  FakeLoc 一键构建（Git Bash / WSL / Linux）
#
#  源码与工具链解耦：所有编译期依赖都指向外部工具链目录，
#  源码可以放在任意路径。
#
#  用法:
#     ./scripts/build.sh              # release
#     ./scripts/build.sh debug        # debug
# ===========================================================================
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---- 工具链位置（按需修改）----
export JAVA_HOME="${JAVA_HOME:-D:/Java/Java21}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-D:/Android/toolchain/gradle-home}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-D:/Android/toolchain/sdk}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"

VARIANT="${1:-release}"
shift || true

echo "============================================================"
echo " FakeLoc 构建"
echo "  PROJECT          = $PROJ"
echo "  JAVA_HOME        = $JAVA_HOME"
echo "  ANDROID_SDK_ROOT = $ANDROID_SDK_ROOT"
echo "  GRADLE_USER_HOME = $GRADLE_USER_HOME"
echo "  VARIANT          = $VARIANT"
echo "============================================================"

cd "$PROJ"

CAP="$(echo "${VARIANT:0:1}" | tr '[:lower:]' '[:upper:]')${VARIANT:1}"
./gradlew ":app:assemble${CAP}" --stacktrace "$@"

echo
echo "============================================================"
echo " 构建成功，产物："
find "app/build/outputs/apk/${VARIANT}" -name '*.apk' -type f 2>/dev/null || true
echo "============================================================"
