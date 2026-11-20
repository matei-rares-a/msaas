@echo off
cd /d "%~dp0"
node scripts\infra\demo-launcher.mjs --stop
set "result=%errorlevel%"
pause
exit /b %result%
