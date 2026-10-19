@echo off
setlocal
cd /d "%~dp0"
if not exist lib\mongodb-driver-sync-5.11.1.jar (
  echo Missing MongoDB Java Driver. See lib\README.md.
  exit /b 1
)
if not exist out mkdir out
javac -encoding UTF-8 -cp "lib\*" -sourcepath src -d out "src\com\networkgame\Launcher.java" "src\com\networkgame\server\DatabaseStatus.java"
if errorlevel 1 exit /b 1
if not exist client-game mkdir client-game
jar --create --file "client-game\PoliceRobberClient.jar" --main-class com.networkgame.client.ClientLauncher -C out com/networkgame/client
if errorlevel 1 exit /b 1
echo Build successful.
