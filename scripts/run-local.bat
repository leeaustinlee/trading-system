@echo off
set SCRIPT_DIR=%~dp0
set PROJECT_DIR=%SCRIPT_DIR%..

if exist "%PROJECT_DIR%\.env" (
  for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%PROJECT_DIR%\.env") do (
    if not "%%~A"=="" (
      set "%%~A=%%~B"
    )
  )
)

if /i "%LINE_ENABLED%"=="true" (
  if "%LINE_CHANNEL_ACCESS_TOKEN%"=="" if "%LINE_TOKEN%"=="" (
    echo [run-local] 錯誤：LINE_ENABLED=true 但 LINE_CHANNEL_ACCESS_TOKEN 未設定
    exit /b 1
  )
  if "%LINE_TO%"=="" (
    echo [run-local] 錯誤：LINE_ENABLED=true 但 LINE_TO 未設定
    exit /b 1
  )
)

cd /d "%PROJECT_DIR%"
mvn spring-boot:run -Dspring-boot.run.profiles=local
