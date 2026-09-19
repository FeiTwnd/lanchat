@echo off
chcp 65001 >nul
rem ===========================================================================
rem 局域网聊天程序 - 清理构建产物（Windows）
rem 用法: scripts\clean.bat [--all]
rem ===========================================================================
setlocal
cd /d "%~dp0.."

if exist build rmdir /s /q build
if exist dist rmdir /s /q dist
echo 已删除构建产物: build\、dist\

if "%~1"=="--all" (
    if exist data\users.txt del /q data\users.txt
    if exist data\history rmdir /s /q data\history
    if exist data\received rmdir /s /q data\received
    if exist data\export rmdir /s /q data\export
    mkdir data\history 2>nul
    mkdir data\received 2>nul
    mkdir data\export 2>nul
    echo 已删除运行期数据，并重建目录骨架
) else (
    echo 提示: 如需同时清理运行期数据，请执行 scripts\clean.bat --all
)
endlocal
