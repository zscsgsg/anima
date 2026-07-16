@echo off
REM Anima launcher for Windows CMD (UTF-8)
REM For PATH-wide access, run: bin\install.bat
chcp 65001 >nul 2>&1
setlocal

set "JAR=%~dp0lib\anima.jar"

if not exist "%JAR%" (
    echo [Anima] JAR not found: %JAR%
    echo Build first:  cd /d "%~dp0" ^&^& mvn package -DskipTests
    exit /b 1
)

java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar "%JAR%" %*
