@echo off
set OUT=%~dp0..\logs\trigger-postmarket.txt
echo === %DATE% %TIME% === > "%OUT%"

echo --- list scheduler jobs --- >> "%OUT%"
curl -s --max-time 5 http://localhost:8888/api/scheduler/jobs >> "%OUT%" 2>&1
echo. >> "%OUT%"

echo --- trigger postmarket-data-prep (force) --- >> "%OUT%"
curl -s -X POST --max-time 60 "http://localhost:8888/api/scheduler/trigger/postmarket-data-prep?force=true" >> "%OUT%" 2>&1
echo. >> "%OUT%"

echo --- pending tasks --- >> "%OUT%"
curl -s --max-time 5 "http://localhost:8888/api/ai/tasks/pending?type=POSTMARKET" >> "%OUT%" 2>&1
echo. >> "%OUT%"

echo --- today's tasks --- >> "%OUT%"
curl -s --max-time 5 "http://localhost:8888/api/orchestration/tasks/today" >> "%OUT%" 2>&1
echo. >> "%OUT%"

echo === done === >> "%OUT%"
