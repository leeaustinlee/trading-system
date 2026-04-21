# Trading System Runbook

> 版本：P2 全部 DONE（2026-04-21）  
> 伺服器：`localhost:8080`，DB：`localhost:3330/trading_system`

---

## 1. 排程總覽

| 時間 | Job | OrchestrationStep | 關鍵 Workflow |
|------|-----|-------------------|--------------|
| 08:30 週一~五 | PremarketDataPrepJob | PREMARKET_DATA_PREP | — |
| 08:45 週一~五 | PremarketNotifyJob | PREMARKET_NOTIFY | PremarketWorkflowService |
| 09:25 週一~五 | OpenDataPrepJob | OPEN_DATA_PREP | — |
| 09:30 週一~五 | FinalDecision0930Job | FINAL_DECISION | IntradayDecisionWorkflowService |
| 每小時 | HourlyIntradayGateJob | HOURLY_GATE | HourlyGateWorkflowService |
| 每5分鐘 | FiveMinuteMonitorJob | FIVE_MINUTE_MONITOR | — |
| 12:00 週一~五 | MiddayReviewJob | MIDDAY_REVIEW | — |
| 14:00 週一~五 | AftermarketReview1400Job | AFTERMARKET_REVIEW | — |
| 15:05 週一~五 | PostmarketDataPrepJob | POSTMARKET_DATA_PREP | — |
| 15:30 週一~五 | PostmarketAnalysis1530Job | POSTMARKET_ANALYSIS | PostmarketWorkflowService |
| 17:00 週一~五 | T86DataPrepJob | T86_DATA_PREP | — |
| 18:00 週一~五 | WatchlistRefreshJob | WATCHLIST_REFRESH | WatchlistWorkflowService |
| 19:00 週一~五 | TomorrowPlan1800Job | TOMORROW_PLAN | — |
| 19:00 週五 | WeeklyTradeReviewJob | WEEKLY_TRADE_REVIEW | — |

所有 Job 預設 `enabled: false`；在 `application.yml` 中個別開啟。

---

## 2. 分層管線（P0~P2）

```
[15:30] TradeReview + Attribution    ← PostmarketWorkflowService Step 1a
[15:30] ThemeStrength.evaluateAll()  ← PostmarketWorkflowService Step 2b
        ↓
[09:30] Regime → Theme → Ranking → Setup → Timing → Risk → Execution
        ↓
[Review] PositionReviewService → ExitRegimeIntegrationEngine override
        ↓
[Weekly] attribution + benchmark + bounded recommendations
```

---

## 3. 異常碼與處置

### 3.1 OrchestrationStep 狀態

| 狀態 | 意義 | 處置 |
|------|------|------|
| `RUNNING` | Job 執行中 | 正常，等待 |
| `DONE` | 當日已完成 | 同一天再次觸發會被 skip |
| `FAILED` | 執行失敗 | 查 `scheduler_execution_log`，手動重跑 |
| `null` | 尚未執行 | 正常（盤前狀態）|

### 3.2 關鍵 Log 關鍵字

| Log 關鍵字 | 意義 |
|-----------|------|
| `already DONE today, skip` | 幂等保護生效，本日 step 已完成 |
| `[PostmarketWorkflow] 每日交易回顧+歸因完成` | Step 1a 正常 |
| `[PostmarketWorkflow] ThemeStrength 評估完成` | Step 2b 正常 |
| `[IntradayDecisionWorkflow] 啟動分層管線` | 09:30 管線啟動 |
| `[IntradayDecisionWorkflow] 管線完成` | 09:30 管線正常結束 |
| `[PositionReview] P2.3 override` | ExitRegime 覆蓋觸發 |
| `[WeeklyTradeReview] 基準比較 marketVerdict=` | Benchmark 正常 |
| `SCORE_DIVERGENCE_HIGH` | Java 與 Claude 評分差 ≥ 2.5，觸發 veto |

### 3.3 錯誤碼

| 場景 | 訊息 | 原因 | 解法 |
|------|------|------|------|
| TWSE MIS API 失敗 | `getQuotes failed` | API 逾時/停機 | 降級：跳過報價更新，候選清單保留昨日資料 |
| DB 連線失敗 | `Unable to acquire JDBC Connection` | MySQL 未啟動 | `sudo systemctl start mysql` |
| LINE 通知失敗 | `LINE notify error` | token 失效 | 更新 `score_config.line_notify_token` |
| Claude 研究逾時 | `claude-research-request.json 未更新` | Claude Code agent 未執行 | 手動觸發 `POST /api/ai/research/write-request` |
| Veto：SCORE_DIVERGENCE_HIGH | veto log | Java/Claude 評分差距大 | 重新執行 Claude 研究後補回填 |
| ThemeStrength 失敗 | `computeThemeStrength 失敗` | theme_snapshot 無今日資料 | 手動呼叫 `POST /api/themes/compute?date=YYYY-MM-DD` |

---

## 4. 降級策略

### 4.1 外部 API 失敗
- TWSE MIS API 失敗 → `candidateScanService` 回傳空清單 → Job 繼續，僅跳過報價更新
- MarketBreadth API 失敗 → `breadth=N/A` 寫入 log，market_snapshot 仍建立（無 breadth 欄位）

### 4.2 管線各層失敗
- `TradeReviewService` 失敗 → `runDailyTradeReview()` 以 `warn` 吞下例外，後續步驟繼續
- `ThemeStrengthService` 失敗 → `computeThemeStrength()` 以 `warn` 吞下例外，不影響 09:30
- `ExitRegimeIntegrationEngine`：`regimeType=null` 或 `themeStage=null` → 無任何 override，position review 正常進行
- `BenchmarkAnalyticsService` 失敗 → weekly summary 顯示 `benchmark=N/A`，不中斷 review

### 4.3 即時報價不可用
- `PositionReviewService`：`quoteAvailable=false` → 寫入 review log（reason: 即時報價不可用），不做任何狀態更新

---

## 5. 手動補跑指令

### 5.1 REST API 手動觸發

```bash
# 查詢當日 orchestration 狀態
curl http://localhost:8080/api/scheduler/jobs

# 手動觸發任意 step（重置 FAILED/DONE 並重跑）
curl -X POST "http://localhost:8080/api/scheduler/trigger/postmarket-analysis"
curl -X POST "http://localhost:8080/api/scheduler/trigger/final-decision"
curl -X POST "http://localhost:8080/api/scheduler/trigger/postmarket-data-prep"
curl -X POST "http://localhost:8080/api/scheduler/trigger/weekly-review"
curl -X POST "http://localhost:8080/api/scheduler/trigger/premarket-data-prep"

# 強制重跑（忽略 DONE 保護）
curl -X POST "http://localhost:8080/api/scheduler/trigger/postmarket-analysis?force=true"
```

### 5.2 盤後完整補跑序列

```bash
# 當日盤後若 15:05 / 15:30 都失敗
curl -X POST "http://localhost:8080/api/scheduler/trigger/postmarket-data-prep?force=true"
sleep 30
curl -X POST "http://localhost:8080/api/scheduler/trigger/postmarket-analysis?force=true"
```

### 5.3 手動重建乾淨 DB（dry run）

```bash
# 依序執行所有 migration（V1b 必須在 V1 之後、V2 之前）
mysql -u root -p trading_system < sql/V1__init_schema.sql
mysql -u root -p trading_system < sql/V1b__create_jpa_managed_tables.sql
mysql -u root -p trading_system < sql/V2__seed_enums_and_configs.sql
mysql -u root -p trading_system < sql/V3__add_indexes.sql
mysql -u root -p trading_system < sql/V4__position_close_fields.sql
mysql -u root -p trading_system < sql/V5__ai_research_log.sql
mysql -u root -p trading_system < sql/V6__external_probe_log.sql
mysql -u root -p trading_system < sql/V7__ai_task_queue.sql
mysql -u root -p trading_system < sql/V8__workflow_correctness_ai_orchestration.sql
mysql -u root -p trading_system < sql/V10__momentum_chase_strategy.sql
mysql -u root -p trading_system < sql/V11__market_regime_decision.sql
mysql -u root -p trading_system < sql/V12__stock_ranking_snapshot.sql
mysql -u root -p trading_system < sql/V13__setup_decision_log.sql
mysql -u root -p trading_system < sql/V14__execution_timing_decision.sql
mysql -u root -p trading_system < sql/V15__portfolio_risk_decision.sql
mysql -u root -p trading_system < sql/V16__execution_decision_log.sql
mysql -u root -p trading_system < sql/V17__theme_strength_decision.sql
mysql -u root -p trading_system < sql/V18__trade_attribution.sql
mysql -u root -p trading_system < sql/V19__bounded_learning_config.sql
mysql -u root -p trading_system < sql/V20__benchmark_analytics.sql
# 啟動 Spring Boot → ddl-auto: update 補建 JPA managed tables（若 V1b 已跑則跳過）
```

### 5.4 Claude 研究補回填

```bash
# 補填某檔候選股的 AI 評分
curl -X PUT http://localhost:8080/api/candidates/2330/ai-scores \
  -H "Content-Type: application/json" \
  -d '{"claudeScore":8.5,"claudeResearchNote":"手動補填"}'

# 補填題材評分
curl -X PUT "http://localhost:8080/api/themes/snapshots/AI散熱/claude-scores" \
  -H "Content-Type: application/json" \
  -d '{"claudeThemeScore":7.0,"claudeResearchNote":"手動補填"}'
```

### 5.5 每週 benchmark / 歸因手動補跑

```bash
# 若週五 WeeklyTradeReviewJob 失敗
curl -X POST "http://localhost:8080/api/scheduler/trigger/weekly-review?force=true"
```

---

## 6. 健康檢查

```bash
# 應用是否存活
curl http://localhost:8080/actuator/health

# 近期 scheduler 執行記錄
curl "http://localhost:8080/api/scheduler/logs?limit=20"

# 當日 orchestration step 狀態
curl http://localhost:8080/api/scheduler/jobs | jq '.[] | {name,status,lastRun}'

# 持倉狀態
curl http://localhost:8080/api/positions/open

# 儀表板
curl http://localhost:8080/api/dashboard/current
```

---

## 7. Bounded Learning 管理

```sql
-- 查看現有允許 keys
SELECT config_key, config_value FROM score_config
WHERE config_key = 'learning.allowed.keys';

-- 新增允許的 key（逗號分隔）
UPDATE score_config
SET config_value = 'timing.tolerance.delay_pct_max,risk.max_position_count'
WHERE config_key = 'learning.allowed.keys';

-- 空字串 = 允許所有 PARAM_ADJUST/TAG_FREQUENCY/RISK_CONTROL（backward compat）
UPDATE score_config SET config_value = '' WHERE config_key = 'learning.allowed.keys';
```

---

## 8. 已知限制（v1）

| 項目 | 限制 | 計畫 |
|------|------|------|
| `tradedThemeAvgGain` | 回退為 marketAvgGain（TradeAttributionOutput 無 themeTag） | v2 補充 themeTag 到 attribution |
| `sessionHighPrice` | 以 `dayHigh` 近似，非持倉期間最高 | v2 從歷史報價取最高 |
| `marketGrade` 在 PositionReviewService | 固定為 "B"，不從 market_snapshot 取 | v2 整合 |
| `themeRank / finalThemeScore` in PositionDecisionInput | null，未整合 | v2 整合 |
