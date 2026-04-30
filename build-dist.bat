@echo off
echo ====================================
echo  AI Studio — 一键构建发布包
echo ====================================

cd /d "%~dp0"

echo [1/4] 构建 Maven 项目...
call mvn package -DskipTests -q
if errorlevel 1 (
    echo 构建失败！请检查 IDEA 错误信息。
    pause
    exit /b 1
)

echo [2/4] 更新 app.jar...
copy /Y "target\ele-business-java-1.0.0.jar" "dist\app.jar"

echo [3/4] 同步前端文件...
if exist "dist\frontend" rd /s /q "dist\frontend"
mkdir "dist\frontend"
xcopy /Y /I "frontend\*" "dist\frontend\"

echo [4/4] 打包 Electron（portable）...
cd electron
set CSC_IDENTITY_AUTO_DISCOVERY=false
call node_modules\.bin\electron-builder.cmd --win
cd ..

echo.
echo ====================================
echo  完成！单文件 exe 位于：
echo  dist-electron\AI Studio 1.0.0.exe
echo ====================================
pause
