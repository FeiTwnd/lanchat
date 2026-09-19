@echo off
chcp 65001 >nul
rem ===========================================================================
rem 局域网聊天程序 - 自动化测试（Windows）
rem 用法: scripts\test.bat [unit^|integration^|all]
rem ===========================================================================
setlocal
cd /d "%~dp0.."

set MODE=%1
if "%MODE%"=="" set MODE=all

if not exist "build\classes" (
    echo [提示] 未找到编译产物，先执行构建（跳过测试）...
    call scripts\build.bat --skip-tests
)

set CP=build\classes;build\test-classes
set EXIT_CODE=0

echo ==============================================
echo  局域网聊天程序 - 自动化测试（模式: %MODE%）
echo ==============================================

if "%MODE%"=="unit" goto unit
if "%MODE%"=="integration" goto integration
if "%MODE%"=="all" goto unit

:unit
echo.
echo ^>^>^> 单元测试
java -cp "%CP%" com.chat.test.TestRunner
if errorlevel 1 set EXIT_CODE=1
if "%MODE%"=="unit" goto done

:integration
echo.
echo ^>^>^> 集成测试
java -cp "%CP%" com.chat.test.IntegrationTest
if errorlevel 1 set EXIT_CODE=1

:done
echo.
if "%EXIT_CODE%"=="0" (
    echo 全部测试通过
) else (
    echo 存在失败的测试用例，请查看上方输出
)
endlocal & exit /b %EXIT_CODE%
