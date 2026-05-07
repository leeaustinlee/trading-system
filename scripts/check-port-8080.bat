@echo off
set OUT=%~dp0..\logs\port-8888-owner.txt
echo === %DATE% %TIME% === > "%OUT%"
echo --- svchost services for PID 5172 --- >> "%OUT%"
tasklist /SVC /FI "PID eq 5172" >> "%OUT%" 2>&1
echo --- all listeners on 8888 --- >> "%OUT%"
netstat -anob | findstr /I /C:":8888" /C:"can not obtain ownership" >> "%OUT%" 2>&1
echo --- powershell get-nettcpconnection --- >> "%OUT%"
powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort 8888 -ErrorAction SilentlyContinue | Select-Object LocalAddress,LocalPort,State,OwningProcess | Format-Table -AutoSize | Out-String" >> "%OUT%" 2>&1
powershell -NoProfile -Command "Get-Process -Id 5172 -ErrorAction SilentlyContinue | Format-List Id,ProcessName,Path,CommandLine | Out-String" >> "%OUT%" 2>&1
echo === done === >> "%OUT%"
