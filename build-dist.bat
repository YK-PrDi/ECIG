@echo off
setlocal enabledelayedexpansion
chcp 65001 > nul

rem ============================================================
rem  AI Studio — 一键清缓存 + 打包（含内嵌 JRE）
rem  用法：
rem    build-dist.bat              正常构建（复用已有 dist/runtime 加速）
rem    build-dist.bat --clean      连同 dist/runtime 一起全清后重裁 JRE
rem    build-dist.bat --skip-jre   跳过 jlink（用户机器需自带 Java 17+）
rem ============================================================

cd /d "%~dp0"

set "CLEAN_RUNTIME=0"
set "SKIP_JRE=0"
:parse_args
if "%~1"=="" goto after_args
if /I "%~1"=="--clean"    set "CLEAN_RUNTIME=1"
if /I "%~1"=="--skip-jre" set "SKIP_JRE=1"
shift
goto parse_args
:after_args

echo ============================================================
echo  AI Studio 打包流程
echo  清 dist/dist-electron/target:      是
echo  清 dist/runtime (重裁 JRE):        %CLEAN_RUNTIME% (--clean 启用)
echo  跳过内嵌 JRE:                       %SKIP_JRE% (--skip-jre 启用)
echo ============================================================
echo.

rem ── 1/7 清缓存 ──────────────────────────────────────────────
echo [1/7] 清理 dist / dist-electron / target ...
if exist "dist-electron" rd /s /q "dist-electron"
if exist "target"        rd /s /q "target"
if "%CLEAN_RUNTIME%"=="1" (
    if exist "dist" rd /s /q "dist"
) else (
    rem 保留 dist\runtime 复用，其余 dist 子项全清
    if exist "dist\app.jar"   del /q  "dist\app.jar"
    if exist "dist\frontend"  rd /s /q "dist\frontend"
)
if not exist "dist" mkdir "dist"

rem ── 2/7 定位 Maven ─────────────────────────────────────────
echo [2/7] 定位 Maven ...
where mvn > nul 2>&1
if errorlevel 1 (
    echo  [错误] 找不到 mvn，请把 Maven 的 bin 目录加入 PATH，或安装 Maven 后重试。
    pause
    exit /b 1
)
for /f "delims=" %%i in ('where mvn') do (
    set "MVN_CMD=%%i"
    goto :mvn_found
)
:mvn_found
echo        使用: %MVN_CMD%

rem ── 3/7 Maven 构建 ─────────────────────────────────────────
echo [3/7] Maven 构建 (clean package -DskipTests)...
call mvn clean package -DskipTests -q
if errorlevel 1 (
    echo  [错误] Maven 构建失败，请检查上方日志。
    pause
    exit /b 1
)

rem 找构建产物
set "APP_JAR="
for %%f in (target\ele-business-java-*.jar) do (
    echo %%~nf | findstr /E ".original" > nul
    if errorlevel 1 set "APP_JAR=%%f"
)
if "%APP_JAR%"=="" (
    echo  [错误] 未在 target\ 找到 Spring Boot 可执行 jar。
    pause
    exit /b 1
)
echo        产物: %APP_JAR%

rem ── 4/7 拷贝 jar 与前端 ───────────────────────────────────
echo [4/7] 拷贝 app.jar 与前端资源到 dist\ ...
copy /Y "%APP_JAR%" "dist\app.jar" > nul
if exist "dist\frontend" rd /s /q "dist\frontend"
mkdir "dist\frontend"
xcopy /Y /I /E /Q "frontend\*" "dist\frontend\" > nul

rem ── 5/7 内嵌 JRE（jlink） ──────────────────────────────────
if "%SKIP_JRE%"=="1" (
    echo [5/7] 跳过 jlink。用户机器需自带 Java 17+ 才能运行 app.jar。
    goto after_jre
)

if exist "dist\runtime\bin\java.exe" (
    if "%CLEAN_RUNTIME%"=="0" (
        echo [5/7] 复用已有 dist\runtime（如需重裁请用 --clean）
        goto after_jre
    )
)

echo [5/7] 定位 JDK 并用 jlink 裁出内嵌 JRE ...
set "JDK_HOME="
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\jlink.exe" set "JDK_HOME=%JAVA_HOME%"
)
if "%JDK_HOME%"=="" (
    for /d %%d in ("%USERPROFILE%\.jdks\*") do (
        if exist "%%d\bin\jlink.exe" (
            set "JDK_HOME=%%d"
            goto :jdk_found
        )
    )
)
:jdk_found
if "%JDK_HOME%"=="" (
    echo  [错误] 找不到可用的 JDK（需带 jlink，JDK 17+）。
    echo         请设置 JAVA_HOME 指向 JDK 根目录，或在 %USERPROFILE%\.jdks\ 下放一个 JDK。
    echo         或用 --skip-jre 跳过内嵌 JRE（用户机器需自备 Java）。
    pause
    exit /b 1
)
echo        使用 JDK: %JDK_HOME%

if exist "dist\runtime" rd /s /q "dist\runtime"
"%JDK_HOME%\bin\jlink.exe" --module-path "%JDK_HOME%\jmods" --add-modules ALL-MODULE-PATH --no-header-files --no-man-pages --compress=2 --output "dist\runtime"
if errorlevel 1 (
    echo  [错误] jlink 失败。
    pause
    exit /b 1
)

rem Smoke test
"dist\runtime\bin\java.exe" -version
if errorlevel 1 (
    echo  [错误] 内嵌 JRE 启动测试失败。
    pause
    exit /b 1
)
:after_jre

rem ── 6/7 打包 Electron ─────────────────────────────────────
echo [6/7] 打包 Electron (electron-builder --win) ...
if not exist "electron\node_modules\.bin\electron-builder.cmd" (
    echo  [错误] 未找到 electron\node_modules。请先进入 electron\ 执行 npm install。
    pause
    exit /b 1
)
pushd electron
set "CSC_IDENTITY_AUTO_DISCOVERY=false"
call node_modules\.bin\electron-builder.cmd --win
set "EB_ERR=%errorlevel%"
popd
if not "%EB_ERR%"=="0" (
    echo  [错误] electron-builder 失败（exit %EB_ERR%）。
    pause
    exit /b %EB_ERR%
)

rem ── 7/7 完成 ──────────────────────────────────────────────
echo.
echo ============================================================
echo  打包完成，产物：
for %%f in (dist-electron\*.exe) do echo    dist-electron\%%~nxf
echo    dist-electron\win-unpacked\AI Studio.exe  (免安装)
echo ============================================================
echo.
pause
endlocal
