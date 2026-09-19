@echo off
chcp 65001 >nul
rem ===========================================================================
rem 局域网聊天程序 - 启动服务器（Windows）
rem 用法: scripts\run-server.bat [--console]
rem ===========================================================================
setlocal
cd /d "%~dp0.."

if not exist "build\classes" (
    echo [提示] 未找到编译产物，先执行构建...
    call scripts\build.bat --skip-tests
)

echo 正在启动聊天服务器（端口读取 config\chat.properties，默认 9527）...
java -Djava.util.logging.SimpleFormatter.format="%%1$tF %%1$tT [%%4$s] %%3$s - %%5$s%%n" -cp "build\classes" com.chat.server.ChatServer %*
endlocal
