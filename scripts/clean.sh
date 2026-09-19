#!/usr/bin/env bash
# ============================================================================
# 局域网聊天程序 - 清理构建产物（Linux / macOS）
# 用法: ./scripts/clean.sh [--all]
#   默认仅删除 build/ 与 dist/ 构建产物
#   --all 额外删除运行期数据（data/users.txt、聊天记录、接收与导出文件）
# 注意: --all 会删除用户数据，执行前请确认已备份
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

rm -rf build dist
echo "已删除构建产物: build/、dist/"

if [[ "${1:-}" == "--all" ]]; then
    rm -rf data/users.txt data/history data/received data/export
    mkdir -p data/history data/received data/export
    echo "已删除运行期数据（用户数据、聊天记录、接收与导出文件）"
    echo "目录骨架已重建: data/history、data/received、data/export"
else
    echo "提示: 如需同时清理运行期数据，请执行 ./scripts/clean.sh --all"
fi
