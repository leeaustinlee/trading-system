@echo off
set OUT=%~dp0..\logs\trigger-postmarket-analysis.txt
echo === %DATE% %TIME% === > "%OUT%"

echo --- preview postmarket-analysis (no notification) --- >> "%OUT%"
curl -s -X POST --max-time 30 "http://localhost:8888/api/scheduler/trigger/postmarket-analysis?force=true&preview=true" >> "%OUT%" 2>&1
echo. >> "%OUT%"

echo --- request.json after --- >> "%OUT%"
type "D:\ai\stock\claude-research-request.json" >> "%OUT%" 2>&1
echo. >> "%OUT%"

echo === done === >> "%OUT%"
