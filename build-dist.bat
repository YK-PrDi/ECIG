@echo off
setlocal enabledelayedexpansion
chcp 65001 > nul

rem ============================================================
rem  AI Studio — 一键清缓存 + 打包（含内嵌 JRE）
rem  用法：
rem    build-dist.bat              正常构建（复用已有 dist/runtime 加速 + 自动 bump patch 版本号）
rem    build-dist.bat --clean      连同 dist/runtime 一起全清后重裁 JRE
rem    build-dist.bat --skip-jre   跳过 jlink（用户机器需自带 Java 17+）
rem    build-dist.bat --no-bump    跳过自动 bump electron/package.json 的 patch 版本号
rem ============================================================

cd /d "%~dp0"

set "CLEAN_RUNTIME=0"
set "SKIP_JRE=0"
set "SKIP_BUMP=0"
:parse_args
if "%~1"=="" goto after_args
if /I "%~1"=="--clean"    set "CLEAN_RUNTIME=1"
if /I "%~1"=="--skip-jre" set "SKIP_JRE=1"
if /I "%~1"=="--no-bump"  set "SKIP_BUMP=1"
shift
goto parse_args
:after_args

echo ============================================================
echo  AI Studio 打包流程
echo  清 dist/dist-electron/target:      是
echo  清 dist/runtime (重裁 JRE):        %CLEAN_RUNTIME% (--clean 启用)
echo  跳过内嵌 JRE:                       %SKIP_JRE% (--skip-jre 启用)
echo  自动 bump version (patch+1):        当 SKIP_BUMP=%SKIP_BUMP% 为 0 时执行 (--no-bump 跳过)
echo ============================================================
echo.

rem ── 预检：脏数据提示 + 自动 bump version ──────────────────
echo [预检 1/2] 检查 frontend/data/categories/ 工作树状态（防止把测试残留打进 release）...
git status --short frontend/data/categories/ 2>nul
echo.
echo 上面如果有 "??" 或 "M" 开头的文件，是未入库的本地导入产物 / 修改。
echo 这些会被一并打进 release exe；如要排除请 Ctrl-C 中止，git stash 或先 commit 后再来。
echo 直接回车继续打包（会带上当前工作树状态）。
pause

if "%SKIP_BUMP%"=="1" (
    echo [预检 2/2] --no-bump 已启用，跳过版本号 bump。
    goto after_bump
)
echo [预检 2/2] 自动 bump electron/package.json 的 patch 版本号 ...
set "NEW_VERSION="
for /f "delims=" %%v in ('powershell -NoProfile -ExecutionPolicy Bypass -Command "$txt = Get-Content -Raw electron\package.json; $m = [regex]::Match($txt, '\"version\"\s*:\s*\"(\d+)\.(\d+)\.(\d+)\"'); if (-not $m.Success) { Write-Error 'version not found'; exit 1 }; $new = '{0}.{1}.{2}' -f $m.Groups[1].Value, $m.Groups[2].Value, ([int]$m.Groups[3].Value + 1); $out = $txt -replace '(\"version\"\s*:\s*\")(\d+\.\d+\.\d+)(\")', ('${1}' + $new + '${3}'); $utf8NoBom = New-Object System.Text.UTF8Encoding($false); [System.IO.File]::WriteAllText((Resolve-Path 'electron\package.json'), $out, $utf8NoBom); Write-Output $new"') do set "NEW_VERSION=%%v"
if "%NEW_VERSION%"=="" (
    echo  [错误] 自动 bump 失败（PowerShell 不可用 / package.json 格式异常）。
    echo         请手动改 electron\package.json 的 version 字段后用 --no-bump 重跑。
    pause
    exit /b 1
)
echo        新版本号: %NEW_VERSION%
:after_bump
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
