# Scheduler Plan

## 固定任務
- 08:10 PremarketDataPrepJob
- 08:30 PremarketNotifyJob
- 09:01 OpenDataPrepJob
- 09:30 FinalDecision0930Job
- 10:05 / 11:05 / 12:05 / 13:05 HourlyIntradayGateJob
- 11:00 MiddayReviewJob
- 14:00 AftermarketReview1400Job
- 15:05 PostmarketDataPrepJob
- 15:30 PostmarketAnalysis1530Job
- 18:10 T86DataPrepJob
- 18:30 TomorrowPlan1800Job
- 08:25 / 15:25 ExternalProbeHealthJob（可選）

## 5 分鐘監控
- `FiveMinuteMonitorJob` 由 Hourly Gate 動態決定是否啟用
- 只在事件觸發時通知，不固定發送
