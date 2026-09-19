#!/usr/bin/env bash
# ============================================================================
# 局域网聊天程序 - 编译构建脚本（Linux / macOS）
# 用法: ./scripts/build.sh [--skip-tests]
# 说明: 纯 javac 编译，不依赖 Maven/Gradle；--skip-tests 可跳过自动化测试
# ============================================================================
set -euo pipefail

# 切换到项目根目录（脚本所在目录的上一级），保证任何位置执行都可用
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

MAIN_SRC="src/main/java"
TEST_SRC="src/test/java"
MAIN_OUT="build/classes"
TEST_OUT="build/test-classes"
DIST_DIR="dist"

SKIP_TESTS="false"
if [[ "${1:-}" == "--skip-tests" ]]; then
    SKIP_TESTS="true"
fi

echo "=============================================="
echo " 局域网聊天程序 - 构建"
echo " 项目目录: ${ROOT_DIR}"
echo "=============================================="

# ---------- 1. 环境检查 ----------
if ! command -v javac >/dev/null 2>&1; then
    echo "[错误] 未找到 javac，请先安装 JDK 17 或更高版本并配置 PATH"
    exit 1
fi

JAVA_VERSION="$(javac -version 2>&1 | awk '{print $2}' | cut -d. -f1)"
if [[ "${JAVA_VERSION}" -lt 17 ]]; then
    echo "[错误] 需要 JDK 17 或更高版本，当前为 ${JAVA_VERSION}"
    exit 1
fi
echo "[1/5] 环境检查通过，javac 版本: $(javac -version 2>&1)"

# ---------- 2. 清理旧产物 ----------
rm -rf "${MAIN_OUT}" "${TEST_OUT}" "${DIST_DIR}"
mkdir -p "${MAIN_OUT}" "${TEST_OUT}" "${DIST_DIR}"

# ---------- 3. 编译主源码 ----------
echo "[2/5] 编译主源码..."
find "${MAIN_SRC}" -name '*.java' > build/main-sources.txt
javac -encoding UTF-8 -d "${MAIN_OUT}" @build/main-sources.txt
echo "      主源码编译完成，共 $(find "${MAIN_OUT}" -name '*.class' | wc -l) 个类文件"

# ---------- 4. 编译测试源码 ----------
echo "[3/5] 编译测试源码..."
find "${TEST_SRC}" -name '*.java' > build/test-sources.txt
javac -encoding UTF-8 -cp "${MAIN_OUT}" -d "${TEST_OUT}" @build/test-sources.txt
echo "      测试源码编译完成，共 $(find "${TEST_OUT}" -name '*.class' | wc -l) 个类文件"

# ---------- 5. 运行测试 ----------
if [[ "${SKIP_TESTS}" == "false" ]]; then
    echo "[4/5] 运行单元测试..."
    java -cp "${MAIN_OUT}:${TEST_OUT}" com.chat.test.TestRunner | tail -6
    echo "      运行集成测试..."
    java -cp "${MAIN_OUT}:${TEST_OUT}" com.chat.test.IntegrationTest | tail -5
else
    echo "[4/5] 已按参数要求跳过测试"
fi

# ---------- 6. 打包可执行 jar ----------
echo "[5/5] 打包可执行 jar..."
java -cp "${MAIN_OUT}:${TEST_OUT}" com.chat.test.JarPackager \
    "${DIST_DIR}/chat-server.jar" com.chat.server.ChatServer "${MAIN_OUT}"
java -cp "${MAIN_OUT}:${TEST_OUT}" com.chat.test.JarPackager \
    "${DIST_DIR}/chat-client.jar" com.chat.client.ChatClientApp "${MAIN_OUT}"
java -cp "${MAIN_OUT}:${TEST_OUT}" com.chat.test.JarPackager \
    "${DIST_DIR}/chat-test.jar" com.chat.test.TestRunner "${MAIN_OUT}" "${TEST_OUT}"

# 把配置与脚本复制到 dist，使 dist 目录可独立分发
cp -r config "${DIST_DIR}/"
mkdir -p "${DIST_DIR}/data/history" "${DIST_DIR}/data/received" "${DIST_DIR}/data/export"

echo "=============================================="
echo " 构建成功"
echo " 服务器: java -jar ${DIST_DIR}/chat-server.jar"
echo " 客户端: java -jar ${DIST_DIR}/chat-client.jar"
echo " 测试:   java -jar ${DIST_DIR}/chat-test.jar"
echo "=============================================="
