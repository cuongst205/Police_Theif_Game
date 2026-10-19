@echo off
cd /d "%~dp0"
if not exist data\mongodb mkdir data\mongodb
mongodb\bin\mongod.exe --dbpath "%~dp0data\mongodb" --bind_ip 127.0.0.1 --port 27017
