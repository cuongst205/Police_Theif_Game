@echo off
cd /d "%~dp0"
if not exist client-game\PoliceRobberClient.jar call build.bat
if errorlevel 1 exit /b 1
call client-game\run-game.bat %*
