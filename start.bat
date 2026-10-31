@echo off
rem Change to the script's own directory so ./data always lives under the project.
cd /d "%~dp0"

set "JAR=target\knowledge-base-0.1.0.jar"

where java >nul 2>nul
if errorlevel 1 (
    echo [ERROR] 'java' not found in PATH. Please install JDK 17+ and add it to PATH.
    pause
    exit /b 1
)

if not exist "%JAR%" (
    echo [ERROR] %JAR% not found.
    echo Please build first: mvn -DskipTests clean package
    pause
    exit /b 1
)

title Knowledge Base
echo Starting Knowledge Base. Default URL: http://127.0.0.1:8080 (port can be changed in Settings page). Press Ctrl+C to stop.
java -jar "%JAR%"
pause
