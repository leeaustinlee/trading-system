@echo off
set OUT=%~dp0..\logs\probe-8888.txt
echo === %DATE% %TIME% === > "%OUT%"
echo --- netstat 8888 --- >> "%OUT%"
netstat -ano | findstr ":8888" >> "%OUT%" 2>&1
echo --- tasklist java --- >> "%OUT%"
tasklist /FI "IMAGENAME eq java.exe" /V >> "%OUT%" 2>&1
echo --- curl health --- >> "%OUT%"
curl -s -o "%~dp0..\logs\probe-8888-body.txt" -w "http_code=%%{http_code}\n" --max-time 10 http://localhost:8888/actuator/health >> "%OUT%" 2>&1
echo --- body --- >> "%OUT%"
type "%~dp0..\logs\probe-8888-body.txt" >> "%OUT%" 2>&1
echo. >> "%OUT%"
echo === done === >> "%OUT%"
