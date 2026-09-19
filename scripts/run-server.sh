#!/usr/bin/env bash
# ============================================================================
# 局域网聊天程序 - 启动服务器（Linux / macOS）
# 用法: ./scripts/run-server.sh [--console]
#   --console  以控制台模式启动（无图形界面，适合远程/无显示环境）
# 说明: 若未构建，会自动先执行 build.sh
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

MAIN_OUT="build/classes"

if [[ ! -d "${MAIN_OUT}" ]]; then
    echo "[提示] 未找到编译产物，先执行构建..."
    "${SCRIPT_DIR}/build.sh" --skip-tests
fi

if ! command -v java >/dev/null 2>&1; then
    echo "[错误] 未找到 java，请先安装 JDK 17 或更高版本"
    exit 1
fi

echo "正在启动聊天服务器（端口读取 config/chat.properties，默认 9527）..."
java -Djava.util.logging.SimpleFormatter.format='%1$tF %1$tT [%4$s] %3$s - %5$s%n' \
     -cp "${MAIN_OUT}" com.chat.server.ChatServer "$@"
