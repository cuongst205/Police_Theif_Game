@echo off
cd /d "%~dp0"
if not exist out call build.bat
if errorlevel 1 exit /b 1
java -cp "out;lib\*" com.networkgame.Launcher server
