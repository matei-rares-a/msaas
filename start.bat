@echo off
cd /d "%~dp0"
node scripts\infra\demo-launcher.mjs --start
set "result=%errorlevel%"
if not "%result%"=="0" pause
exit /b %result%
