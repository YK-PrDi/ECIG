@echo off
setlocal enabledelayedexpansion
chcp 65001 > nul

rem ============================================================
rem  AI Studio - 快速打包脚本（包含所有诊断和修复）
rem  自动处理：关闭旧进程、清理、编译、打包
rem  Usage: quick-build.bat
rem ============================================================

cd /d "%~dp0"

echo ============================================================
echo  AI Studio 快速打包流程
echo  1. 关闭运行中的 AI Studio 进程
echo  2. 清理旧的打包文件
echo  3. Maven 编译（使用便携版 Maven）
echo  4. Electron 打包
echo ============================================================
echo.

rem ---- 步骤 1: 关闭旧进程 ----
echo [1/4] 关闭运行中的 AI Studio...
taskkill /F /IM "AI Studio.exe" >nul 2>&1
if %errorlevel%==0 (
    echo        已关闭运行中的实例
    timeout /t 3 /nobreak >nul
) else (
    echo        没有运行中的实例
)
echo.

rem ---- 步骤 2: 清理旧文件 ----
echo [2/4] 清理 dist-electron 和 target...
if exist "dist-electron" rd /s /q "dist-electron"
if exist "target" rd /s /q "target"
echo        清理完成
echo.

rem ---- 步骤 3: Maven 编译 ----
echo [3/4] Maven 编译项目...

rem 查找便携版 Maven
set "MAVEN_HOME=C:\Users\19144\maven-portable\apache-maven-3.9.9"
set "MVN_CMD=%MAVEN_HOME%\bin\mvn.cmd"

if not exist "%MVN_CMD%" (
    echo  [ERROR] 未找到便携版 Maven: %MVN_CMD%
    echo          请修改脚本中的 MAVEN_HOME 路径
    pause
    exit /b 1
)

echo        使用 Maven: %MVN_CMD%
call "%MVN_CMD%" clean compile -DskipTests -q
if errorlevel 1 (
    echo  [ERROR] Maven 编译失败
    pause
    exit /b 1
)
echo        编译成功
echo.

rem ---- 步骤 4: 完整打包 ----
echo [4/4] 执行完整打包（build-dist.bat）...
echo        此步骤需要 3-5 分钟，请耐心等待...
echo.

rem 设置 Maven 到 PATH（build-dist.bat 需要）
set "PATH=%MAVEN_HOME%\bin;%PATH%"

call build-dist.bat --no-bump
set "BUILD_ERR=%errorlevel%"

echo.
if "%BUILD_ERR%"=="0" (
    echo ============================================================
    echo  ✅ 打包成功！
    echo.
    echo  打包文件位置：
    echo    dist-electron\AI Studio Setup 1.2.3.exe    ^(安装版^)
    echo    dist-electron\win-unpacked\AI Studio.exe   ^(便携版^)
    echo.
    echo  新版本包含：
    echo    ✓ 完整日志系统 ^(app.log + jvm.log^)
    echo    ✓ 启动环境诊断
    echo    ✓ 保存操作详细日志
    echo    ✓ 路径校验诊断
    echo.
    echo  日志位置：用户数据目录/logs/
    echo ============================================================
) else (
    echo ============================================================
    echo  ❌ 打包失败，错误代码: %BUILD_ERR%
    echo  请检查上方日志输出
    echo ============================================================
    pause
    exit /b %BUILD_ERR%
)

echo.
echo 按任意键退出...
pause >nul
endlocal
