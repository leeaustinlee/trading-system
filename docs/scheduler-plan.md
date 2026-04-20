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
## v2.1 時間 + 狀態雙重驅動規格

最新完整規格見 `docs/workflow-correctness-ai-orchestration-spec.md`。

正式規格：

- DataPrep / Workflow 負責建立 `ai_task`。
- Claude file bridge 只負責 submit Claude 結果，不作為正式建 task 手段。
- Codex 只處理 `CLAUDE_DONE` task。
- Notify / FinalDecision job 只讀已完成結果；若 AI 未完成，必須明確 fallback，不可假裝完整 AI 決策。

| 時間 | Step | 正式動作 | AI 依賴 |
|---:|---|---|---|
| 08:10 | PremarketDataPrepJob | 建 `PREMARKET` task | 無 |
| 08:20 | Claude | submit `PREMARKET` | task 必須已存在 |
| 08:28 | Codex | submit `PREMARKET` codex-result | `CLAUDE_DONE` |
| 08:30 | PremarketNotifyJob | 讀 `CODEX_DONE` 或明確 fallback | FULL_AI_READY 優先 |
| 09:01 | OpenDataPrepJob | 建 `OPENING` task | 無 |
| 09:20 | Claude | submit `OPENING` | task 必須已存在 |
| 09:28 | Codex | submit `OPENING` codex-result | `CLAUDE_DONE` |
| 09:30 | FinalDecision0930Job | 優先讀 `OPENING`，Codex 未完成不得 ENTER | `require_codex=true` |
| 10:40 | Midday data prep | 建 `MIDDAY` task | 無 |
| 10:50 | Claude | submit `MIDDAY` | task 必須已存在 |
| 10:58 | Codex | submit `MIDDAY` codex-result | `CLAUDE_DONE` |
| 11:00 | MiddayReviewJob | 讀 `MIDDAY` 結果或明確 fallback | FULL_AI_READY 優先 |
| 14:00 | AftermarketReview1400Job | 今日操作檢討，Codex/Java-only | 不依賴 Claude |
| 15:05 | PostmarketDataPrepJob | 建 `POSTMARKET` task | 無 |
| 15:20 | Claude | submit `POSTMARKET` | task 必須已存在 |
| 15:28 | Codex | submit `POSTMARKET` codex-result | `CLAUDE_DONE` |
| 15:30 | PostmarketAnalysis1530Job | 只讀 `POSTMARKET` 結果，不可晚建 task | FULL_AI_READY 優先 |
| 17:40 | T86DataPrepJob | 建 `T86_TOMORROW` task | T86 source ready |
| 17:50 | Claude | submit `T86_TOMORROW` | task 必須已存在 |
| 17:58 | Codex | submit `T86_TOMORROW` codex-result | `CLAUDE_DONE` |
| 18:00/18:30 | TomorrowPlan1800Job | 讀 `T86_TOMORROW` 結果或明確 fallback | FULL_AI_READY 優先 |
