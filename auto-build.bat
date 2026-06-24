@echo off
setlocal enabledelayedexpansion
chcp 65001 > nul

cd /d "%~dp0"

set "CLEAN_ALL=0"
:parse_args
if "%~1"=="" goto after_args
if /I "%~1"=="--clean" set "CLEAN_ALL=1"
shift
goto parse_args
:after_args

echo ============================================================
echo  AI Studio Auto Build
echo ============================================================
echo.

rem Step 1: Kill old process
echo [1/5] Stopping AI Studio...
taskkill /F /IM "AI Studio.exe" >nul 2>&1
if %errorlevel%==0 (
    echo        Stopped
    timeout /t 3 /nobreak >nul
) else (
    echo        Not running
)
echo.

rem Step 2: Clean
echo [2/5] Cleaning dist-electron and target...
if exist "dist-electron" (
    rd /s /q "dist-electron" 2>nul
    if exist "dist-electron" (
        timeout /t 2 /nobreak >nul
        rd /s /q "dist-electron" 2>nul
    )
)
if exist "target" rd /s /q "target"

if "%CLEAN_ALL%"=="1" (
    if exist "dist" (
        echo        Clean dist/ including runtime
        rd /s /q "dist"
    )
)
echo        Done
echo.

rem Step 3: Find Maven
echo [3/5] Locating Maven...

set "MVN_CMD="

rem Priority 1: Portable Maven
set "PORTABLE_MAVEN=%USERPROFILE%\maven-portable\apache-maven-3.9.9\bin\mvn.cmd"
if exist "%PORTABLE_MAVEN%" (
    set "MVN_CMD=%PORTABLE_MAVEN%"
    echo        Found portable: %PORTABLE_MAVEN%
    goto maven_found
)

rem Priority 2: System PATH
where mvn.cmd >nul 2>&1
if %errorlevel%==0 (
    for /f "delims=" %%i in ('where mvn.cmd') do (
        set "MVN_CMD=%%i"
        echo        Found system: %%i
        goto maven_found
    )
)

rem Priority 3: MAVEN_HOME
if defined MAVEN_HOME (
    if exist "%MAVEN_HOME%\bin\mvn.cmd" (
        set "MVN_CMD=%MAVEN_HOME%\bin\mvn.cmd"
        echo        Found MAVEN_HOME: %MVN_CMD%
        goto maven_found
    )
)

rem Not found
echo  [ERROR] Maven not found
echo.
echo  Please:
echo    1. Download Maven to: %USERPROFILE%\maven-portable\apache-maven-3.9.9\
echo    2. Install Maven and add to PATH
echo    3. Set MAVEN_HOME environment variable
echo.
pause
exit /b 1

:maven_found
echo.

rem Step 4: Maven compile
echo [4/5] Compiling with Maven...
call "%MVN_CMD%" clean compile -DskipTests -q
if errorlevel 1 (
    echo  [ERROR] Maven compile failed
    pause
    exit /b 1
)
echo        Compile success
echo.

rem Step 5: Electron package
echo [5/5] Packaging with Electron...
echo        This takes 3-5 minutes, please wait...
echo.

rem Set Maven to PATH
for %%i in ("%MVN_CMD%") do set "MAVEN_BIN_DIR=%%~dpi"
set "PATH=%MAVEN_BIN_DIR%;%PATH%"

rem Call main build script
if "%CLEAN_ALL%"=="1" (
    call build-dist.bat --no-bump --clean
) else (
    call build-dist.bat --no-bump
)
set "BUILD_ERR=%errorlevel%"

echo.
if "%BUILD_ERR%"=="0" (
    echo ============================================================
    echo  Build Success!
    echo.
    echo  Output:
    for %%f in (dist-electron\*.exe) do (
        set "SIZE="
        for %%s in (%%f) do set "SIZE=%%~zs"
        set /a "SIZE_MB=!SIZE! / 1048576"
        echo    %%~nxf  ^(!SIZE_MB! MB^)
    )
    if exist "dist-electron\win-unpacked\AI Studio.exe" (
        for %%s in ("dist-electron\win-unpacked\AI Studio.exe") do set "SIZE=%%~zs"
        set /a "SIZE_MB=!SIZE! / 1048576"
        echo    win-unpacked\AI Studio.exe  ^(!SIZE_MB! MB^) [Portable]
    )
    echo.
    echo  New Features:
    echo    - Full logging system
    echo    - Startup diagnostics
    echo    - Save operation logs
    echo    - HTTP clipboard fallback
    echo.
    echo  Log location: user-data-dir/logs/
    echo ============================================================
) else (
    echo ============================================================
    echo  Build Failed, error code: %BUILD_ERR%
    echo ============================================================
)

echo.
echo Press any key to exit...
pause >nul
endlocal
