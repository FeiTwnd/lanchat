@echo off
chcp 65001 >nul
rem ===========================================================================
rem 局域网聊天程序 - 启动客户端（Windows）
rem 用法: scripts\run-client.bat [--local-server]
rem ===========================================================================
setlocal
cd /d "%~dp0.."

if not exist "build\classes" (
    echo [提示] 未找到编译产物，先执行构建...
    call scripts\build.bat --skip-tests
)

echo 正在启动聊天客户端...
java -Djava.util.logging.SimpleFormatter.format="%%1$tF %%1$tT [%%4$s] %%3$s - %%5$s%%n" -cp "build\classes" com.chat.client.ChatClientApp %*
endlocal
