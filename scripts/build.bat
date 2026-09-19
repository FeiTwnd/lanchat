@echo off
chcp 65001 >nul
rem ===========================================================================
rem 局域网聊天程序 - 编译构建脚本（Windows）
rem 用法: scripts\build.bat [--skip-tests]
rem 说明: 纯 javac 编译，不依赖 Maven/Gradle
rem ===========================================================================
setlocal enabledelayedexpansion
cd /d "%~dp0.."

set MAIN_SRC=src\main\java
set TEST_SRC=src\test\java
set MAIN_OUT=build\classes
set TEST_OUT=build\test-classes
set DIST_DIR=dist

set SKIP_TESTS=false
if "%~1"=="--skip-tests" set SKIP_TESTS=true

echo ==============================================
echo  局域网聊天程序 - 构建（Windows）
echo ==============================================

where javac >nul 2>nul
if errorlevel 1 (
    echo [错误] 未找到 javac，请先安装 JDK 17 或更高版本并配置 PATH
    exit /b 1
)
javac -version
echo [1/5] 环境检查通过

if exist "%MAIN_OUT%" rmdir /s /q "%MAIN_OUT%"
if exist "%TEST_OUT%" rmdir /s /q "%TEST_OUT%"
if exist "%DIST_DIR%" rmdir /s /q "%DIST_DIR%"
mkdir "%MAIN_OUT%" 2>nul
mkdir "%TEST_OUT%" 2>nul
mkdir "%DIST_DIR%" 2>nul

echo [2/5] 编译主源码...
dir /s /b "%MAIN_SRC%\*.java" > build\main-sources.txt
javac -encoding UTF-8 -d "%MAIN_OUT%" @build\main-sources.txt
if errorlevel 1 (
    echo [错误] 主源码编译失败
    exit /b 1
)

echo [3/5] 编译测试源码...
dir /s /b "%TEST_SRC%\*.java" > build\test-sources.txt
javac -encoding UTF-8 -cp "%MAIN_OUT%" -d "%TEST_OUT%" @build\test-sources.txt
if errorlevel 1 (
    echo [错误] 测试源码编译失败
    exit /b 1
)

if "%SKIP_TESTS%"=="false" (
    echo [4/5] 运行单元测试...
    java -cp "%MAIN_OUT%;%TEST_OUT%" com.chat.test.TestRunner
    echo       运行集成测试...
    java -cp "%MAIN_OUT%;%TEST_OUT%" com.chat.test.IntegrationTest
) else (
    echo [4/5] 已按参数要求跳过测试
)

echo [5/5] 打包可执行 jar...
java -cp "%MAIN_OUT%;%TEST_OUT%" com.chat.test.JarPackager "%DIST_DIR%\chat-server.jar" com.chat.server.ChatServer "%MAIN_OUT%"
java -cp "%MAIN_OUT%;%TEST_OUT%" com.chat.test.JarPackager "%DIST_DIR%\chat-client.jar" com.chat.client.ChatClientApp "%MAIN_OUT%"
java -cp "%MAIN_OUT%;%TEST_OUT%" com.chat.test.JarPackager "%DIST_DIR%\chat-test.jar" com.chat.test.TestRunner "%MAIN_OUT%" "%TEST_OUT%"

xcopy /e /i /q config "%DIST_DIR%\config" >nul
mkdir "%DIST_DIR%\data\history" 2>nul
mkdir "%DIST_DIR%\data\received" 2>nul
mkdir "%DIST_DIR%\data\export" 2>nul

echo ==============================================
echo  构建成功
echo  服务器: java -jar %DIST_DIR%\chat-server.jar
echo  客户端: java -jar %DIST_DIR%\chat-client.jar
echo  测试:   java -jar %DIST_DIR%\chat-test.jar
echo ==============================================
endlocal
