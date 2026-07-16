@echo off
REM Anima Launcher for Windows
REM Add bin\ to PATH to run 'anima' from any directory.
REM One-click setup: run bin\install.bat

chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
REM Go up one level to project root (bin -> root)
for %%i in ("%SCRIPT_DIR%..") do set ROOT_DIR=%%~fi
set JAR_FILE=%ROOT_DIR%\lib\anima.jar

REM Check if Java is available
where java >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Java is not found. Please install JDK 21+ from https://adoptium.net/
    pause
    exit /b 1
)

REM Check Java version
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set JAVA_VERSION=%%v
)
set JAVA_VERSION=%JAVA_VERSION:"=%
echo Java: %JAVA_VERSION%

if not exist "%JAR_FILE%" (
    echo [ERROR] anima.jar not found at: %JAR_FILE%
    echo Build first:  cd /d "%ROOT_DIR%" ^&^& mvn package -DskipTests
    pause
    exit /b 1
)

java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar "%JAR_FILE%" %*
exit /b %ERRORLEVEL%
