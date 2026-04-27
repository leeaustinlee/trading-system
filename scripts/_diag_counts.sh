#!/usr/bin/env bash
for cls in DailyHealthCheckJob PremarketDataPrepJob PremarketNotifyJob ExternalProbeHealthJob OpenDataPrepJob FinalDecision0930Job FiveMinuteMonitorJob HourlyIntradayGateJob PostmarketDataPrepJob T86DataPrepJob MiddayReviewJob PostmarketReview14Job; do
  url="http://localhost:8080/actuator/metrics/tasks.scheduled.execution?tag=className:com.austin.trading.scheduler.${cls}"
  v=$(curl -s "$url" | python3 -c 'import sys,json
try:
    d=json.load(sys.stdin)
    print(d["measurements"][0]["value"])
except:
    print(0)')
  echo "${cls} = ${v}"
done
