@echo off
set OUT=%~dp0..\logs\spring-boot-run.log
echo === run at %DATE% %TIME% === > "%OUT%"
cd /d D:\ai\stock\trading-system
mvn spring-boot:run -Dspring-boot.run.profiles=local >> "%OUT%" 2>&1
echo === exit code %ERRORLEVEL% at %DATE% %TIME% === >> "%OUT%"
