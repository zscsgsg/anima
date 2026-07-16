@echo off
REM Anima One-Click Installer for Windows
REM Adds bin\ to user PATH so you can run 'anima' from anywhere

setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
for %%i in ("%SCRIPT_DIR%..") do set ROOT_DIR=%%~fi
set "BIN_DIR=%ROOT_DIR%\bin"
set "JAR_FILE=%ROOT_DIR%\lib\anima.jar"

echo ============================================
echo   Anima Installer
echo ============================================
echo.

REM Check if jar exists
if not exist "%JAR_FILE%" (
    echo [WARN] anima.jar not found.
    echo   Run: cd /d "%ROOT_DIR%" ^&^& mvn package -DskipTests
    echo   Then re-run this installer.
    echo.
)

REM Check Java
where java >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Java not found. Please install JDK 21+ from https://adoptium.net/
    pause
    exit /b 1
)
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set JAVA_VERSION=%%v
set JAVA_VERSION=%JAVA_VERSION:"=%
echo Java: %JAVA_VERSION%
echo.

REM Check DEEPSEEK_API_KEY
if "%DEEPSEEK_API_KEY%"=="" (
    echo [INFO] DEEPSEEK_API_KEY is not set.
    echo   Anima will ask for it on first run, or set it with:
    echo     set DEEPSEEK_API_KEY=sk-your-key
    echo   Get key: https://platform.deepseek.com/api_keys
    echo.
) else (
    echo [OK] DEEPSEEK_API_KEY is configured.
    echo.
)

REM Check if bin dir is already in PATH
echo %%PATH%% | findstr /i /c:"%BIN_DIR%" >nul 2>&1
if %ERRORLEVEL% equ 0 (
    echo [OK] Anima is already in your PATH.
    echo.
    echo Quick test:
    echo   anima --help
    echo   anima --terminal
    goto :done
)

echo Adding Anima to your user PATH...
echo   %BIN_DIR%
echo.

setx PATH "%PATH%;%BIN_DIR%" >nul 2>&1
if %ERRORLEVEL% equ 0 (
    echo [OK] Anima has been added to your PATH.
    echo.
    echo ============================================
    echo   IMPORTANT: Close and reopen your terminal
    echo   for the changes to take effect.
    echo ============================================
    echo.
    echo After reopening, you can run from ANY directory:
    echo   anima --terminal
    echo   anima --help
) else (
    echo [ERROR] Failed to update PATH. You may need to:
    echo   1. Run this script as Administrator
    echo   2. Or manually add this directory to PATH:
    echo      %BIN_DIR%
)

:done
echo.
pause
