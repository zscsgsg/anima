@echo off
setlocal
cd /d "%~dp0"
if "%DEEPSEEK_API_KEY%"=="" echo [WARN] DEEPSEEK_API_KEY not set. Set it or add to environment.
mvn exec:java -Dexec.mainClass=com.anima.AnimaApp -Dexec.args="--terminal" -q
