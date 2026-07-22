@echo off
chcp 65001 > nul
title AI 产品图生成系统 - 本地 GPU 启动

echo ============================================
echo  AI 产品图生成系统 - 本地 GPU 版
echo  GPU: RTX 4090 24GB
echo ============================================
echo.

REM 检查 SD WebUI 是否已启动
netstat -ano | findstr ":7860" > nul
if %errorlevel%==0 (
    echo [✓] Stable Diffusion WebUI 已在运行
    echo     访问: http://localhost:7860
) else (
    echo [×] Stable Diffusion WebUI 未运行
    echo.
    echo [!] 请先启动 SD WebUI:
    echo     1. 下载整合包（见指南）
    echo     2. 解压到 D:\stable-diffusion-webui\
    echo     3. 双击运行: D:\stable-diffusion-webui\webui-user.bat
    echo.
    echo [!] 记得在 webui-user.bat 中添加启动参数:
    echo     set COMMANDLINE_ARGS=--api --listen --port 7860 --xformers --no-half-vae
    echo.
    pause
    exit /b 1
)

echo.
echo [→] 正在启动 Java 后端...
echo.

cd /d F:\java\ele-business-java
set "MAVEN_HOME=C:\Users\19144\maven-portable\apache-maven-3.9.9"
set "PATH=%MAVEN_HOME%\bin;%PATH%"

call mvn.cmd spring-boot:run

pause
