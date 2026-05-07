@echo off
set OUT=%~dp0..\logs\probe-port-2.txt
echo === probe-2 at %DATE% %TIME% === > "%OUT%"
echo --- pid 5172 --- >> "%OUT%"
tasklist /FI "PID eq 5172" /V >> "%OUT%" 2>&1
echo --- all java/javaw --- >> "%OUT%"
tasklist /FI "IMAGENAME eq java.exe" /V >> "%OUT%" 2>&1
tasklist /FI "IMAGENAME eq javaw.exe" /V >> "%OUT%" 2>&1
echo --- mvn /maven --- >> "%OUT%"
wmic process where "CommandLine like '%%spring-boot:run%%' or CommandLine like '%%trading-system%%' or CommandLine like '%%mvn%%'" get ProcessId,Name,CommandLine /format:list >> "%OUT%" 2>&1
echo --- netstat 8888 --- >> "%OUT%"
netstat -ano | findstr ":8888" >> "%OUT%" 2>&1
echo --- curl health (10s timeout) --- >> "%OUT%"
curl -s -o "%~dp0..\logs\probe-port-2-body.txt" -w "http_code=%%{http_code}\n" --max-time 10 http://localhost:8888/actuator/health >> "%OUT%" 2>&1
echo === done === >> "%OUT%"
