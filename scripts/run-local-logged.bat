@echo off
REM Wrapper to launch run-local.bat with log capture
set SCRIPT_DIR=%~dp0
set LOGFILE=%SCRIPT_DIR%..\logs\run-local-launch.log
mkdir "%SCRIPT_DIR%..\logs" 2>nul

echo === Launch attempt at %DATE% %TIME% === >> "%LOGFILE%"
where mvn >> "%LOGFILE%" 2>&1
where java >> "%LOGFILE%" 2>&1
echo JAVA_HOME=%JAVA_HOME% >> "%LOGFILE%"
echo PATH=%PATH% >> "%LOGFILE%"
echo --- starting run-local.bat --- >> "%LOGFILE%"

call "%SCRIPT_DIR%run-local.bat" >> "%LOGFILE%" 2>&1

echo --- run-local.bat exited with code %ERRORLEVEL% at %DATE% %TIME% --- >> "%LOGFILE%"
