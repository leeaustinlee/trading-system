@echo off
REM Cleanup: kill any leftover cmd windows that earlier tried to run mvn spring-boot:run
for /f "tokens=2" %%P in ('wmic process where "Name='cmd.exe' and CommandLine like '%%spring-boot:run%%'" get ProcessId /format:value ^| findstr "ProcessId"') do (
  echo killing cmd PID %%P
  taskkill /PID %%P /T /F >nul 2>&1
)
REM Also kill any rogue java that tried to bind the app port.
for /f "tokens=2" %%P in ('wmic process where "Name='java.exe' and CommandLine like '%%trading-system%%'" get ProcessId /format:value ^| findstr "ProcessId"') do (
  echo killing java PID %%P
  taskkill /PID %%P /T /F >nul 2>&1
)

REM Start fresh: use logged wrapper so .env parsing / validation and startup evidence are preserved.
start "trading-system-8888" cmd /k "D:\ai\stock\trading-system\scripts\run-local-logged.bat"
