#!/usr/bin/env bash
# ============================================================================
# 局域网聊天程序 - 自动化测试脚本（Linux / macOS）
# 用法: ./scripts/test.sh [unit|integration|all]
#   unit         仅运行单元测试（47 个用例）
#   integration  仅运行集成测试（7 个用例）
#   all          两者都运行（默认）
# 退出码: 0 表示全部通过，非 0 表示存在失败用例
# ============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

MAIN_OUT="build/classes"
TEST_OUT="build/test-classes"
MODE="${1:-all}"

# ---------- 按需编译 ----------
if [[ ! -d "${MAIN_OUT}" || ! -d "${TEST_OUT}" ]]; then
    echo "[提示] 未找到编译产物，先执行构建（跳过测试）..."
    "${SCRIPT_DIR}/build.sh" --skip-tests
fi

CP="${MAIN_OUT}:${TEST_OUT}"
EXIT_CODE=0

echo "=============================================="
echo " 局域网聊天程序 - 自动化测试"
echo " 模式: ${MODE}"
echo "=============================================="

if [[ "${MODE}" == "unit" || "${MODE}" == "all" ]]; then
    echo ""
    echo ">>> 单元测试（密码安全 / 用户 DAO / 用户服务 / 消息服务 / 文件传输）"
    java -Djava.util.logging.SimpleFormatter.format='%1$tT [%4$s] %3$s - %5$s%n' \
         -cp "${CP}" com.chat.test.TestRunner
    UNIT_CODE=$?
    if [[ ${UNIT_CODE} -ne 0 ]]; then
        EXIT_CODE=1
    fi
fi

if [[ "${MODE}" == "integration" || "${MODE}" == "all" ]]; then
    echo ""
    echo ">>> 集成测试（真实服务器 + 多客户端端到端）"
    java -Djava.util.logging.SimpleFormatter.format='%1$tT [%4$s] %3$s - %5$s%n' \
         -cp "${CP}" com.chat.test.IntegrationTest
    INTEGRATION_CODE=$?
    if [[ ${INTEGRATION_CODE} -ne 0 ]]; then
        EXIT_CODE=1
    fi
fi

echo ""
if [[ ${EXIT_CODE} -eq 0 ]]; then
    echo "全部测试通过"
else
    echo "存在失败的测试用例，请查看上方输出"
fi
exit ${EXIT_CODE}
