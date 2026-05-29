@echo off
setlocal enabledelayedexpansion
chcp 65001 > nul

rem ============================================================
rem  AI Studio - one-shot clean + package (with embedded JRE)
rem  Usage:
rem    build-dist.bat              normal build (reuse dist/runtime + auto bump patch)
rem    build-dist.bat --clean      also nuke dist/runtime and re-jlink
rem    build-dist.bat --skip-jre   skip jlink (user must have Java 17+ on PATH)
rem    build-dist.bat --no-bump    skip auto bump of electron/package.json patch
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
echo  AI Studio build flow
echo  Clean dist/dist-electron/target:    yes
echo  Clean dist/runtime (re-jlink):      %CLEAN_RUNTIME% (--clean to enable)
echo  Skip embedded JRE:                  %SKIP_JRE% (--skip-jre to enable)
echo  Auto bump version (patch+1):        runs when SKIP_BUMP=%SKIP_BUMP% is 0 (--no-bump to skip)
echo ============================================================
echo.

rem ---- preflight: dirty-data check + auto bump version ----
echo [pre 1/2] check working-tree of frontend/data/categories/ (avoid leaking test imports into release)...
git status --short frontend/data/categories/ 2>nul
echo.
echo Lines starting with "??" or " M" above are not committed yet and WILL be packed into the release exe.
echo (auto-continuing in non-interactive mode; use Ctrl-C to abort manually if needed)
echo.

if "%SKIP_BUMP%"=="1" (
    echo [pre 2/2] --no-bump set, skip version bump.
    goto after_bump
)
echo [pre 2/2] auto-bump electron/package.json patch version ...
set "BUMP_PS1=%~dp0_bump_version.ps1"
set "NEW_VERSION="
for /f "usebackq delims=" %%v in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%BUMP_PS1%"`) do set "NEW_VERSION=%%v"
if defined NEW_VERSION goto bump_ok
echo  [ERROR] auto-bump failed (PowerShell missing or package.json malformed).
echo          Edit electron\package.json version manually then re-run with --no-bump.
pause
exit /b 1
:bump_ok
echo        new version: %NEW_VERSION%
:after_bump
echo.

rem ---- 1/7 clean ----
echo [1/7] cleaning dist / dist-electron / target ...
if exist "dist-electron" rd /s /q "dist-electron"
if exist "target"        rd /s /q "target"
if "%CLEAN_RUNTIME%"=="1" (
    if exist "dist" rd /s /q "dist"
) else (
    rem keep dist\runtime, drop everything else
    if exist "dist\app.jar"   del /q  "dist\app.jar"
    if exist "dist\frontend"  rd /s /q "dist\frontend"
)
if not exist "dist" mkdir "dist"

rem ---- 2/7 locate Maven ----
echo [2/7] locating Maven ...
where mvn > nul 2>&1
if errorlevel 1 (
    echo  [ERROR] mvn not on PATH. Install Maven and add bin to PATH.
    pause
    exit /b 1
)
for /f "delims=" %%i in ('where mvn') do (
    set "MVN_CMD=%%i"
    goto :mvn_found
)
:mvn_found
echo        using: %MVN_CMD%

rem ---- 3/7 Maven build ----
echo [3/7] Maven build (clean package -DskipTests) ...
call mvn clean package -DskipTests -q
if errorlevel 1 (
    echo  [ERROR] Maven build failed, see log above.
    pause
    exit /b 1
)

set "APP_JAR="
for %%f in (target\ele-business-java-*.jar) do (
    echo %%~nf | findstr /E ".original" > nul
    if errorlevel 1 set "APP_JAR=%%f"
)
if "%APP_JAR%"=="" (
    echo  [ERROR] Spring Boot fat jar not found in target\.
    pause
    exit /b 1
)
echo        artifact: %APP_JAR%

rem ---- 4/7 copy jar + frontend ----
echo [4/7] copying app.jar + frontend assets to dist\ ...
copy /Y "%APP_JAR%" "dist\app.jar" > nul
if exist "dist\frontend" rd /s /q "dist\frontend"
mkdir "dist\frontend"
xcopy /Y /I /E /Q "frontend\*" "dist\frontend\" > nul

rem ---- 5/7 embedded JRE (jlink) ----
if "%SKIP_JRE%"=="1" (
    echo [5/7] skipping jlink. User machine must have Java 17+ on PATH.
    goto after_jre
)

if exist "dist\runtime\bin\java.exe" (
    if "%CLEAN_RUNTIME%"=="0" (
        echo [5/7] reusing existing dist\runtime (use --clean to re-jlink)
        goto after_jre
    )
)

echo [5/7] locating JDK and jlink-ing embedded JRE ...
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
    echo  [ERROR] no usable JDK with jlink (need JDK 17+).
    echo          Set JAVA_HOME to a JDK root, or drop one under %USERPROFILE%\.jdks\.
    echo          Or use --skip-jre.
    pause
    exit /b 1
)
echo        using JDK: %JDK_HOME%

if exist "dist\runtime" rd /s /q "dist\runtime"
"%JDK_HOME%\bin\jlink.exe" --module-path "%JDK_HOME%\jmods" --add-modules ALL-MODULE-PATH --no-header-files --no-man-pages --compress=2 --output "dist\runtime"
if errorlevel 1 (
    echo  [ERROR] jlink failed.
    pause
    exit /b 1
)

rem smoke test
"dist\runtime\bin\java.exe" -version
if errorlevel 1 (
    echo  [ERROR] embedded JRE smoke test failed.
    pause
    exit /b 1
)
:after_jre

rem ---- 6/7 electron-builder ----
echo [6/7] packaging Electron (electron-builder --win) ...
if not exist "electron\node_modules\.bin\electron-builder.cmd" (
    echo  [ERROR] electron\node_modules missing. Run "npm install" inside electron\ first.
    pause
    exit /b 1
)
pushd electron
set "CSC_IDENTITY_AUTO_DISCOVERY=false"
call node_modules\.bin\electron-builder.cmd --win
set "EB_ERR=%errorlevel%"
popd
if not "%EB_ERR%"=="0" (
    echo  [ERROR] electron-builder failed (exit %EB_ERR%).
    pause
    exit /b %EB_ERR%
)

rem ---- 7/7 done ----
echo.
echo ============================================================
echo  Build complete. Artifacts:
for %%f in (dist-electron\*.exe) do echo    dist-electron\%%~nxf
echo    dist-electron\win-unpacked\AI Studio.exe  (portable)
echo ============================================================
echo.
endlocal
