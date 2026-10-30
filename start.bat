@echo off
chcp 65001 >nul
rem 切换到脚本所在目录：保证在任意位置运行时，./data 数据目录都落在项目内
cd /d "%~dp0"

set "JAR=target\knowledge-base-0.1.0.jar"

where java >nul 2>nul
if errorlevel 1 (
    echo [错误] 未找到 java 命令，请安装 JDK 17+ 并加入 PATH
    pause
    exit /b 1
)

if not exist "%JAR%" (
    echo [错误] 未找到 %JAR%
    echo 请先在项目目录执行: mvn -DskipTests clean package
    pause
    exit /b 1
)

title Knowledge Base
echo Knowledge Base 启动中，默认访问 http://127.0.0.1:8080 （端口可在设置页修改，按 Ctrl+C 停止服务）
java -jar "%JAR%"
pause
