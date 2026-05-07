@echo off
set OUT=%~dp0..\logs\probe-port.txt
echo === probe at %DATE% %TIME% === > "%OUT%"
echo --- netstat 8888 --- >> "%OUT%"
netstat -ano | findstr ":8888" >> "%OUT%" 2>&1
echo --- tasklist java --- >> "%OUT%"
tasklist /FI "IMAGENAME eq java.exe" >> "%OUT%" 2>&1
echo --- curl health --- >> "%OUT%"
curl -s -o nul -w "http_code=%%{http_code}\n" --max-time 3 http://localhost:8888/actuator/health >> "%OUT%" 2>&1
curl -s --max-time 3 http://localhost:8888/actuator/health >> "%OUT%" 2>&1
echo. >> "%OUT%"
echo === done === >> "%OUT%"
