@echo off
setlocal
cd /d "%~dp0"
where java >nul 2>nul
if errorlevel 1 (
  echo Java 17 or newer is required. Install Java, then open this file again.
  pause
  exit /b 1
)
java -jar PoliceRobberClient.jar %*
if errorlevel 1 (
  echo Could not launch the game. Check that Java 17 or newer is installed.
  pause
)
