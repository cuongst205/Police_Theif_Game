@echo off
cd /d "%~dp0"
if not exist out\com\networkgame\server\DatabaseStatus.class call build.bat
if errorlevel 1 exit /b 1
java -cp "out;lib\*" com.networkgame.server.DatabaseStatus
