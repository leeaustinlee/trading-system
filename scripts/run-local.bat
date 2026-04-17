@echo off
if exist .env (
  for /f "usebackq tokens=1,* delims==" %%A in (".env") do (
    if not "%%A"=="" (
      if /i not "%%A:~0,1%"=="#" (
        set "%%A=%%B"
      )
    )
  )
)
mvn spring-boot:run -Dspring-boot.run.profiles=local
