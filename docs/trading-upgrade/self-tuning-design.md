# 台股 AI 交易系統｜AI 自我進化（Self-Tuning）模組設計

> 版本：self-tuning-design v1（2026-05-07）
> 範圍：在 `strategy-design.md` / `code-implementation-plan.md` / `files-to-change.md` / `test-plan.md` 已奠定的「三策略 + Tracking」基礎上，增加「根據 Tracking 結果自動產生調參建議」的可控閉環。
> 定位：**決策輔助 + 統計驗證 + 人工審核**。本模組**不下單**、**不自動覆寫 live config**、**不依單日結果改策略**、**樣本不足不出口建議**、**所有建議皆需 Austin 明確 APPROVE 才會寫入 `score_config`**。
> 與既有文件關係：本文件**不取代** strategy-design.md，只新增「自動分析 → PENDING 建議 → 人工審核 → 可回滾套用 → before/after 追蹤」這條 loop，並補上 strategy-design §6 提到「不自動 apply」的具體機制。

---

## 0. 設計總原則（Hard Rules，破壞任一條即視為設計失敗）

1. **不是自動下單**：本模組產出的是「策略參數調整建議」，不會生成任何下單訊號、不會寫 `position`、不會發 LINE 進場通知。
2. **不是讓 AI 隨便改策略**：所有建議都只能是 `PENDING`；唯有 Austin 透過 `POST /api/strategy-tuning/recommendations/{id}/approve` 明確核可，才會寫入 `score_config`。
3. **觀察 → 統計 → 建議調參 → 人工審核 → 套用 → 追蹤效果**：六步驟皆有持久化紀錄，每一筆建議與套用皆可回滾、可重現、可審計。
4. **不准直接自動改 live config**：`StrategyTuningEngine` 不持有 `ScoreConfigService` 的寫入權限；只有 `StrategyTuningService.approveAndApply` 受保護路徑才能寫入。
5. **不准直接自動下單**：本模組與 `FinalDecisionService` / `PaperTradeService` / `PositionService` 之間是**單向只讀**關係（讀 tracking 表）；不寫任何下單實體。
6. **不准只看單日結果**：所有 rule 皆以「過去 N 個交易日 (預設 N≥20)」為 sample window；單日資料只能進入原始 tracking 表，**不得**直接觸發任何 recommendation。
7. **樣本不足時不可建議調參**：每個 rule 都必須先通過 `sample >= MIN_SAMPLE`（預設 20）才可進入 confidence 計算；否則一律輸出 `INSUFFICIENT_DATA`，由 Engine 主動忽略。
8. **所有建議必須有統計證據**：`evidenceJson` 欄位必須包含 sample size、命中率、平均報酬、標準差、p-value (或 bootstrap CI)、benchmark 對照、命中 rule 的 raw query。沒有這些欄位視為無效建議。
9. **所有建議必須有 confidence**：分為 `HIGH / MEDIUM / LOW / INSUFFICIENT_DATA` 四級，前端 UI / API 必須一起回傳。
10. **所有建議必須有 rollback**：每筆 `strategy_tuning_history`（套用紀錄）必須記錄 `previousValue`，且提供 `POST /api/strategy-tuning/history/{id}/rollback`。
11. **所有建議必須有 history**：每次套用、回滾、撤銷皆寫入 `strategy_tuning_history`，可追溯誰、何時、為什麼。
12. **所有建議必須有 before / after 比較**：套用後系統自動排程「套用前 20 個交易日 vs 套用後 20 個交易日」效果比較，寫入 `strategy_tuning_history.afterReportJson`。
13. **PENDING 不可顯示為「已優化」**：UI / Dashboard / LINE 不得把 `PENDING` 建議呈現為「系統已自我優化」；僅能呈現「待 Austin 審核的調參建議」。

---

## 1. 調參資料來源

| # | 來源表 / Service | 用途 | 對應 Java 檔（已存在） |
|---|---|---|---|
| 1 | `candidate_forward_tracking` | ENTER / WATCH / WAIT 候選 T+1/T+3/T+5/T+10 報酬、MFE、MAE、是否觸停 / 觸標、相對 0050 報酬 | `CandidateForwardTrackingEntity` / `CandidateForwardTrackingRepository` / `CandidateForwardTrackingService` |
| 2 | `missed_rally_tracking` | REJECT / WAIT / WATCH 後 T+1/T+3/T+5/T+10 漲幅；`missed_rally_flag`、`gateName`、`primary_strategy` | `MissedRallyTrackingEntity` / `MissedRallyTrackingService`（現行 T+5 maxReturn ≥ 8% 即 flag）|
| 3 | `final_decision` | 每日最終 ENTER / WAIT / REST + selected stocks + grade + strategy_type + version | `FinalDecisionEntity` / `FinalDecisionService` |
| 4 | `stock_evaluation` | 每檔每日 4 維分數（基本 / 籌碼 / 技術 / 風險）、final_score、java vs claude 分歧 | 既有 `StockEvaluationEngine` 輸出 |
| 5 | `strategy trace`（candidate `strategyTrace*` 欄位） | 該檔走的策略、breakout/pullback/continuation 三分數、entryMode / riskMode / gateStatus | `FinalDecisionCandidateRequest.withStrategyTrace`、`StrategyGateService` |
| 6 | gate reject reason（`gate_failures_json` / `rejectReason`） | 哪一條 gate 把它擋下來：`chased_high_block` / `reject_price_gate` / `tradability_tag_block` / `rr_below_min` / `near_day_high` / `volume_below_min` 等 | `FinalDecisionEngine` / `PriceGateEvaluator` / `VetoEngine` / `BreakoutGate` / `PullbackGate` / `ContinuationGate` |
| 7 | benchmark return | 0050 / TWII 同期 daily return；用於計算 `relative_return_pct`、`outperform_benchmark` | `MarketIndexDailyEntity` / `BenchmarkAnalyticsEngine` |

**唯讀約束**：`StrategyTuningEngine` 僅可透過 Repository 的 read-only 介面（`@Transactional(readOnly = true)`）查詢以上 7 張表；不得呼叫任何 `save()` / `delete()`。

**樣本品質要求**（在進入任何 rule 之前先過濾）：
- `tradingDate` 必須完整（不是 NULL）
- `t5_close_pct` / `mfe_pct` / `mae_pct` 至少其中一欄非 NULL（代表已過 T+5 觀察期）
- 排除盤後手動補資、`tradabilityTag = ALL_DAY_LIMIT_UP`（漲停鎖死）等資料品質可疑的 row
- 若樣本中含 `marketGrade = C` 集體 REST 期，須將該段視為「條件鎖定」單獨彙總，不可混入 A/B 級樣本中作為全域結論

---

## 2. 可調參數分類（Tunable Parameter Catalog）

> 命名沿用 `score_config.config_key` 風格，與 `strategy-design.md §6.1` 對齊；新增的全部進 `score_config` 表，不能寫入 hard-code。

### 2.1 Scoring Thresholds（評分門檻）

| key | 預設 | 範圍 | 用途 |
|---|---:|---|---|
| `scoring.grade_a_plus_min` | 8.5 | [7.5, 9.5] | A+ 桶下限 |
| `scoring.grade_a_min` | 7.5 | [6.5, 8.5] | A 桶下限 |
| `scoring.grade_b_min` | 6.5 | [5.5, 7.5] | B 桶下限（試單） |
| `scoring.enter_min_score` | 6.5 | [5.5, 8.0] | FinalDecisionEngine 進 ENTER 的最低分數 |
| `scoring.watch_min_score` | 5.0 | [4.0, 6.5] | 進 WATCH（會寫 candidate_forward_tracking）的最低分數 |

### 2.2 Strategy Thresholds（策略門檻）

#### Breakout
| key | 預設 | 範圍 | 用途 |
|---|---:|---|---|
| `breakout.min_score` | 7.0 | [6.0, 8.5] | Breakout 桶最低 final score |
| `breakout.volume_ratio_min` | 1.8 | [1.3, 3.0] | 突破當日相對 5 日均量 |
| `breakout.relative_strength_min` | 1.0 | [0.5, 2.0] | 個股 / 大盤同期 RS |
| `breakout.near_high_allowed` | true | bool | 距日高 < 2% 是否允許進場（false 則 hard reject） |
| `breakout.rr_min` | 2.0 | [1.2, 3.0] | RR 下限（Breakout） |
| `breakout.enter_small_enabled` | true | bool | 是否允許 `ENTER_SMALL`（試單） |

#### Pullback
| key | 預設 | 範圍 | 用途 |
|---|---:|---|---|
| `pullback.min_score` | 7.0 | [6.0, 8.5] | Pullback 桶最低 final score |
| `pullback.rr_min` | 2.5 | [1.5, 3.5] | RR 下限（Pullback） |
| `pullback.entry_zone_pct` | 0.015 | [0.005, 0.03] | 距 MA / 頸線 ± % |
| `pullback.support_distance_max` | 0.03 | [0.01, 0.05] | 與支撐最大距離 |

#### Continuation
| key | 預設 | 範圍 | 用途 |
|---|---:|---|---|
| `continuation.min_score` | 7.0 | [6.0, 8.5] | Continuation 桶最低 final score |
| `continuation.rr_min` | 1.5 | [1.0, 2.5] | RR 下限（Continuation） |
| `continuation.volume_ratio_min` | 1.5 | [1.2, 3.0] | 整理後續放量倍率 |
| `continuation.overheat_max` | 3.0 | [2.5, 5.0] | 爆量長黑判定（量比上限） |

### 2.3 Gate Parameters（風控閘門）

| key | 預設 | 範圍 | 用途 |
|---|---:|---|---|
| `gate.near_day_high_reject_threshold` | 0.02 | [0.005, 0.05] | 距日高 < 此值即觸發 nearDayHigh 規則 |
| `gate.chased_high_threshold` | 0.05 | [0.02, 0.08] | 距日內均價 / 開盤上沿 > 此值視為追高 |
| `gate.rr_min` | 1.2 | [1.0, 2.0] | 全域 RR 最低（任何策略都不可低於） |
| `gate.volume_ratio_min` | 1.0 | [0.8, 1.5] | 全域量能下限 |
| `gate.theme_strength_min` | 6.0 | [4.0, 8.0] | 題材強度最低門檻 |
| `gate.sector_strength_min` | 5.0 | [3.0, 7.0] | 族群強度最低門檻 |
| `gate.max_gap_up_pct` | 0.05 | [0.03, 0.08] | 開盤跳空上限 |
| `gate.min_liquidity` | 1000 | [500, 5000] | 最低成交量（張） |

### 2.4 Risk Parameters（持倉風險）

| key | 預設 | 範圍 | 用途 |
|---|---:|---|---|
| `risk.max_position_size_pct` | 0.20 | [0.10, 0.30] | 單檔最大資金比例 |
| `risk.enter_small_position_pct` | 0.10 | [0.05, 0.15] | `ENTER_SMALL` 使用比例 |
| `risk.trailing_stop_step_rules` | JSON | — | trailing 五段門檻（沿用 §4.1） |
| `risk.stop_loss_pct` | 0.05 | [0.03, 0.08] | 預設停損 |
| `risk.take_profit_pct` | 0.06 | [0.04, 0.15] | 第一段停利 |

**共同約束**：每筆 recommendation 變更**單一 key**，且新值必須落在「範圍」內；若 rule 計算出的建議值超出範圍，須 clamp 到邊界並在 `evidenceJson` 標 `clamped=true`。

---

## 3. 調參建議規則（Rule Engine）

> Engine：`StrategyTuningEngine.run(date, lookbackDays)`。每條 rule 都實作 `TuningRule` 介面，分別吐 0-N 筆 `TuningRecommendationDraft`。所有 rule 共用樣本品質過濾與 confidence 計算（§4）。

### Rule A：Reject 後大漲（Gate too strict）
**資料來源**：`missed_rally_tracking`（過去 lookbackDays，預設 30）。
**判定**：
```
sample = COUNT(*) WHERE missed_rally_tracking.original_decision IN ('REJECT','WAIT','WATCH')
                  AND tradingDate ∈ [today-lookbackDays, today-5]   -- 預留 T+5 觀察期
IF sample < 20 → INSUFFICIENT_DATA, skip
avg_t5_max_return  = AVG(maxReturnPct)        WHERE same filter
missed_rally_rate  = SUM(missedRallyFlag=true)/sample
relative_return    = avg_t5_max_return - benchmark.same_period_return
IF avg_t5_max_return >= 6% AND missed_rally_rate >= 25% AND relative_return > 3%:
   建議：依「最常見的 gateName」分層輸出 1-3 筆建議
```
**建議型態**（按 evidence 強度排序，產出 1 條）：
- 若 `gateName ∈ {chased_high_block, near_day_high}` 命中率最高 → 建議 `gate.near_day_high_reject_threshold` +0.005（放寬），且若該 strategy=BREAKOUT 並列「Breakout 不再用 nearDayHigh 作 hard reject」→ `breakout.near_high_allowed = true`
- 若 `gateName = reject_price_gate` 命中率最高 → 建議 `WATCH_NEXT_DAY`（不直接放寬 priceGate，改建議「該標的進入 WATCH 觀察池」之 policy 開關）
- 若 `gateName = rr_below_min` 命中率最高 + `primary_strategy = CONTINUATION` → 建議 `continuation.rr_min` -0.1
- 通用 fallback → 建議 `ENTER_SMALL`（開啟對應策略的試單模式）

**安全限制**：
- 任一 rule A 建議都不允許把對應 key 一次調超過「預設值的 ±15%」或「範圍 step 的 1 個單位」中的較小者。
- 同一 key 連續 30 日內**最多套用 1 次**。

### Rule B：ENTER 表現差
**資料來源**：`candidate_forward_tracking`（`finalDecision = ENTER`）。
**判定**：
```
sample        = COUNT(*) WHERE finalDecision='ENTER' AND tradingDate ∈ window
IF sample < 20 → INSUFFICIENT_DATA
avg_t5_close  = AVG(t5CloseReturnPct)
win_rate      = SUM(t5CloseReturnPct > 0) / sample
avg_mae       = AVG(maePct)
IF avg_t5_close < 0 AND win_rate < 45% AND avg_mae < -4%:
```
**建議**：
1. `scoring.enter_min_score` +0.3（提高進場門檻）
2. 對應 strategy 的 `*.min_score` +0.3
3. `risk.max_position_size_pct` -0.02（降倉位）
4. `risk.stop_loss_pct` -0.005（縮停損）

**只能擇 1 條送出**；rule 內部依「分布最差的維度」決定。例：若 MAE 失控 → 優先送風控；若 win_rate 低但 MAE 可控 → 優先送門檻。

### Rule C：WATCH 表現好（被低估）
**資料來源**：`candidate_forward_tracking`（`finalDecision = WATCH`）。
**判定**：
```
watch_sample  = COUNT(*) WHERE finalDecision='WATCH' AND tradingDate ∈ window
enter_sample  = COUNT(*) WHERE finalDecision='ENTER' AND tradingDate ∈ window
IF watch_sample < 20 OR enter_sample < 20 → INSUFFICIENT_DATA
watch_t5_avg  = AVG(t5CloseReturnPct) WHERE WATCH
watch_winrate = SUM(t5CloseReturnPct>0)/watch_sample
watch_mfe     = AVG(mfePct)         WHERE WATCH
enter_t5_avg  = AVG(t5CloseReturnPct) WHERE ENTER
enter_winrate = SUM(t5CloseReturnPct>0)/enter_sample
IF watch_t5_avg > enter_t5_avg + 1.5pp
   AND watch_winrate > enter_winrate + 5pp
   AND watch_mfe > enter_mfe:
```
**建議**：
- 部分 WATCH 改 `ENTER_SMALL`：建議將該策略 `*.enter_small_enabled = true` 並降低 `*.min_score` -0.2
- 或建議降低該策略的 strategy gate 門檻（如 `breakout.volume_ratio_min` -0.1）

### Rule D：Breakout 被錯殺
**資料來源**：`missed_rally_tracking` JOIN `candidate_forward_tracking` JOIN `final_decision`，篩 `primary_strategy = BREAKOUT` 且 `finalDecision IN ('REJECT','WATCH')`。
**判定**：
```
sample = ...
IF sample < 20 → INSUFFICIENT_DATA
mfe_avg          = AVG(mfePct)
missed_rally_rate= SUM(missedRallyFlag=true)/sample
IF mfe_avg >= 7% AND missed_rally_rate >= 30%:
   分析 gateName 分布：
   IF top gates ∈ {near_day_high, chased_high_block}:
```
**建議**：
- `breakout.near_high_allowed = true`（不再以 nearDayHigh 作 hard reject）
- 同步建議 Breakout 改走 `riskMode = HIGH_MOMENTUM`、`entryMode = ENTER_SMALL`，**小倉位代替拒絕**
- `risk.enter_small_position_pct` 維持不動（不主動放大倉位）

### Rule E：Pullback 表現弱
**資料來源**：`candidate_forward_tracking`（`primary_strategy = PULLBACK` AND `finalDecision IN ('ENTER','WAIT_PULLBACK')`）。
**判定**：
```
sample = ...
IF sample < 20 → INSUFFICIENT_DATA
mfe_avg         = AVG(mfePct)
close_avg       = AVG(t5CloseReturnPct)
late_recovery   = SUM(WAIT_PULLBACK 後第 2-5 天才轉強的比例)
IF mfe_avg < 4% AND close_avg < 1% AND late_recovery > 50%:
```
**建議**：
- `pullback.min_score` +0.3（提高 Pullback 門檻）
- `pullback.entry_zone_pct` -0.003（縮小可接受 entry zone，要求更貼近 MA）
- `pullback.support_distance_max` -0.005（要求量縮 / 支撐更明確）
- 不建議「等更久」；本 rule 明確排斥「過度等待回測」。

### Rule F：Continuation 表現好
**資料來源**：`candidate_forward_tracking`（`primary_strategy = CONTINUATION` AND `finalDecision = ENTER`）。
**判定**：
```
sample = ...
IF sample < 20 → INSUFFICIENT_DATA
win_rate      = SUM(t5CloseReturnPct>0)/sample
mae_avg       = AVG(maePct)
t3_close_avg  = AVG(t3CloseReturnPct)
t5_close_avg  = AVG(t5CloseReturnPct)
IF win_rate >= 55% AND mae_avg > -3% AND t3_close_avg > 2% AND t5_close_avg > 3%:
```
**建議**：
- `continuation.rr_min` -0.1（從 1.5 → 1.4）
- 將 strategy.classifier.priority 調整建議：把 `MOMENTUM_CONT` 從 priority=3 提升到 priority=2（**不自動套用**，僅建議）
- `continuation.min_score` -0.2（放寬最低分數）

> **共用安全限制（所有 rule）**：
> - `previousValue` 必須與 `score_config` 當下值一致；若不一致代表中間有人改過 → 取消本筆建議並標 `STALE_BASELINE`。
> - 任一筆建議僅變更**一個** config key。需要多 key 連動時，拆成多筆 recommendation 共用 `groupId`，approve 時可一次批准一組（仍逐筆寫 history）。
> - Rule 之間若同時對同一 key 提出衝突方向（A 建議放寬、B 建議收緊），優先採「樣本更大、confidence 更高」者；衝突筆同時送 PENDING 並在 `conflictGroupId` 互相連結，UI 會強制 Austin 二選一。

---

## 4. Confidence 設計

每筆 `TuningRecommendationDraft` 都必須計算下列分數（0-1，越高越可信），最後合成 `finalConfidence`：

| 子分 | 計算 | 說明 |
|---|---|---|
| `evidenceScore` | `min(abs(observed - threshold) / threshold, 1.0)` | rule 觸發條件超過閾值多少（越遠越強） |
| `sampleScore` | `min(sample / 60, 1.0)` | sample = MIN_SAMPLE 時為 1/3；sample ≥ 60 飽和為 1.0 |
| `consistencyScore` | 1 - 變異係數（CV = stdDev / |mean|）；clamp [0,1] | 結果穩定度 |
| `riskScore` | 1 - 該 key 變動幅度 / 範圍寬度 | 變動越保守越高 |
| `finalConfidence` | 0.30·evidence + 0.30·sample + 0.25·consistency + 0.15·risk | |

**等級對應**：
| finalConfidence | 等級 | 動作 |
|---|---|---|
| ≥ 0.80 | `HIGH` | 可主動進 PENDING；UI 標綠 |
| 0.60 - 0.80 | `MEDIUM` | 進 PENDING，UI 標黃，描述須附「建議連同下一週 review 一起決定」 |
| 0.40 - 0.60 | `LOW` | 進 PENDING，UI 標灰，預設摺疊；不會出現在 LINE / Daily summary |
| < 0.40 或 sample < MIN_SAMPLE | `INSUFFICIENT_DATA` | 不建立 recommendation，僅寫入 `strategy_tuning_engine_run_log`（debug 用） |

**樣本不足保護**：
- 若 `sample < MIN_SAMPLE (= 20)` → **強制** `INSUFFICIENT_DATA`，不論其他子分多漂亮。
- 若建議方向是 **aggressive**（放寬 gate / 提高倉位 / 降停損），且 `finalConfidence < 0.70` → **強制降級** 為 `LOW`（aggressive 建議不允許 MEDIUM 以下自動上 PENDING；UI 仍可看到但不在 daily summary 顯示）。
- 若樣本期間出現過 `marketGrade = C` 連續超過 3 天 → `consistencyScore × 0.7`（懲罰異常市況樣本）。

---

## 5. Human-in-the-loop 流程

```
 ┌──────────────────────┐
 │ StrategyTuningDailyJob│  每日 19:00（盤後 + tracking T+1 已回填）
 └──────────┬────────────┘
            ▼
 ┌──────────────────────────┐
 │ StrategyTuningEngine.run │  讀 7 個資料來源 + 跑 6 條 rule
 └──────────┬───────────────┘
            ▼
 ┌────────────────────────────────────┐
 │ 過樣本品質 / sample / confidence 過濾 │
 └──────────┬─────────────────────────┘
            ▼
 ┌──────────────────────────────────────────────┐
 │ 寫入 strategy_tuning_recommendation (status=PENDING) │
 └──────────┬───────────────────────────────────────┘
            ▼
 ┌──────────────────────────────────┐
 │ 通知（內部 LINE / Dashboard banner）│  ← 必須清楚標「待 Austin 審核」
 └──────────┬───────────────────────┘
            ▼
 ┌──────────────────────────────────────────────────┐
 │ Austin 在 Dashboard 查看 → APPROVE / REJECT / DEFER │
 └──────────┬───────────────────────────────────────┘
            ▼ (APPROVE 才走以下分支)
 ┌──────────────────────────────────────────────┐
 │ StrategyTuningService.approveAndApply         │
 │  1. 檢查 previousValue 與 score_config 一致    │
 │  2. 寫 strategy_tuning_history (snapshot)     │
 │  3. ScoreConfigService.update(key, newValue)  │
 │  4. recommendation.status = APPLIED           │
 └──────────┬───────────────────────────────────┘
            ▼
 ┌──────────────────────────────────────────────────────┐
 │ 觀察下一輪效果（套用後 20 個交易日）                       │
 │ StrategyTuningAfterReportJob 自動跑 before / after 對照  │
 │ 寫 strategy_tuning_history.afterReportJson + status     │
 │   → IMPROVED / NEUTRAL / WORSE                        │
 │ 若 WORSE → 系統建議 ROLLBACK，但仍由 Austin 決定        │
 └──────────────────────────────────────────────────────┘
```

**關鍵控制點**：
- `PENDING` 預設 7 個自然日有效，過期自動 `EXPIRED`，不會殘留干擾。
- `APPROVE` 端點要求 idempotency key（`recommendationId + previousValue`），同一筆按兩次只生效一次。
- `REJECT` 必須附 `rejectReason`（free text + 預設選項：`太冒進 / 樣本不足 / 與我的判斷相反 / 其他`）；reject 紀錄會回流給 rule engine 作為下次 confidence 的負樣本。
- `ROLLBACK` 是把 `score_config` 改回 `strategy_tuning_history.previousValue`，並寫一筆新的 history（type = `ROLLBACK`），不是刪除原 history。
- **絕對不允許**「自動 approve」開關；不存在這個 flag、不存在隱藏路徑。

---

## 6. 建議 DB / API / Service / Dashboard 設計

> 與 `strategy-design.md §6.1` / `code-implementation-plan.md` 既有命名相容；新增的 entity / service / controller 全部加上 `Tuning` 前綴避免污染既有名稱空間。

### 6.1 Service / Engine 責任邊界

| 元件 | 責任 | 不可做的事 |
|---|---|---|
| `StrategyTuningEngine` | 跑 6 條 rule、計算 confidence、產出 `TuningRecommendationDraft` 列表 | **不寫任何表**；只 return draft list |
| `StrategyTuningService` | 接受 draft 寫入 `strategy_tuning_recommendation`；提供 `approve/reject/rollback`；唯一可呼叫 `ScoreConfigService.update` 的 service | 不直接跑 rule；不繞過 `previousValue` 檢查 |
| `StrategyTuningController` | REST API（list / detail / approve / reject / rollback / history） | 不做業務邏輯 |
| `StrategyTuningDailyJob` | 每日 19:00 觸發 `Engine.run`，把 draft 交給 `Service.persistDrafts` | 不可在週末 / `marketGrade=C` 連續日跳過品質檢查 |
| `StrategyTuningAfterReportJob` | 每日 19:30 對所有 `APPLIED` 且 `appliedAt + 20 trading days <= today` 的 history 跑 before / after 報告 | 不會自動 rollback，只會更新 `afterReportJson` 與 `effectStatus` |

### 6.2 DB Schema（新增 migration `V29__strategy_tuning.sql`）

```sql
CREATE TABLE IF NOT EXISTS strategy_tuning_recommendation (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    created_at          DATETIME NOT NULL,
    rule_code           VARCHAR(32)  NOT NULL,    -- RULE_A / RULE_B ... RULE_F
    config_key          VARCHAR(100) NOT NULL,
    previous_value      VARCHAR(500) NOT NULL,
    suggested_value     VARCHAR(500) NOT NULL,
    direction           VARCHAR(16)  NOT NULL,    -- AGGRESSIVE / DEFENSIVE / NEUTRAL
    sample_size         INT          NOT NULL,
    lookback_days       INT          NOT NULL,
    evidence_score      DECIMAL(5,4),
    sample_score        DECIMAL(5,4),
    consistency_score   DECIMAL(5,4),
    risk_score          DECIMAL(5,4),
    final_confidence    DECIMAL(5,4),
    confidence_level    VARCHAR(24)  NOT NULL,    -- HIGH / MEDIUM / LOW / INSUFFICIENT_DATA
    evidence_json       JSON         NOT NULL,    -- { sample, winRate, avgT5, stdDev, pValue, benchmark, rawQuery }
    rationale           VARCHAR(1000) NOT NULL,   -- 一句話解釋為什麼
    status              VARCHAR(24)  NOT NULL,    -- PENDING / APPROVED / REJECTED / EXPIRED / SUPERSEDED / STALE_BASELINE
    expires_at          DATETIME     NOT NULL,
    group_id            VARCHAR(36),              -- 同一輪相關建議共用
    conflict_group_id   VARCHAR(36),              -- 與哪些建議衝突
    rejected_reason     VARCHAR(500),
    decided_at          DATETIME,
    decided_by          VARCHAR(64),
    INDEX idx_status (status, created_at),
    INDEX idx_config_key (config_key, created_at)
);

CREATE TABLE IF NOT EXISTS strategy_tuning_history (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    recommendation_id   BIGINT,                   -- 對應的 recommendation；ROLLBACK 也記原 id
    type                VARCHAR(24) NOT NULL,     -- APPLY / ROLLBACK / MANUAL_OVERRIDE
    config_key          VARCHAR(100) NOT NULL,
    previous_value      VARCHAR(500) NOT NULL,
    new_value           VARCHAR(500) NOT NULL,
    applied_at          DATETIME    NOT NULL,
    applied_by          VARCHAR(64) NOT NULL,
    note                VARCHAR(500),
    after_report_json   JSON,                     -- before/after 對照（套用 20 交易日後填）
    effect_status       VARCHAR(24),              -- IMPROVED / NEUTRAL / WORSE / PENDING_OBSERVATION
    effect_evaluated_at DATETIME,
    INDEX idx_recommendation_id (recommendation_id),
    INDEX idx_applied_at (applied_at),
    INDEX idx_config_key (config_key, applied_at)
);

CREATE TABLE IF NOT EXISTS strategy_tuning_engine_run_log (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_at          DATETIME NOT NULL,
    lookback_days   INT NOT NULL,
    rules_evaluated INT NOT NULL,
    drafts_produced INT NOT NULL,
    skipped_insufficient_data INT NOT NULL,
    summary_json    JSON
);
```

> `score_config` 不變更 schema；只是新增的 keys 走原本 upsert 路徑。

### 6.3 REST API

```http
GET  /api/strategy-tuning/recommendations?status=PENDING&limit=50
GET  /api/strategy-tuning/recommendations/{id}
POST /api/strategy-tuning/recommendations/{id}/approve
       Body: { confirm: true, note? }
POST /api/strategy-tuning/recommendations/{id}/reject
       Body: { reason: "...", note? }
POST /api/strategy-tuning/recommendations/{id}/defer
       Body: { until: "YYYY-MM-DD" }

GET  /api/strategy-tuning/history?from=...&to=...&configKey=...
GET  /api/strategy-tuning/history/{id}
POST /api/strategy-tuning/history/{id}/rollback
       Body: { reason: "..." }

POST /api/strategy-tuning/engine/run        -- 手動觸發（debug only，預設僅 admin）
GET  /api/strategy-tuning/engine/last-run
```

**安全**：
- `approve` / `rollback` / `engine/run` 必須需要 `X-Austin-Confirm: true` header，避免被腳本誤觸。
- 所有 POST 端點都要記 `applied_by`（從 session / token 取），不允許匿名。

### 6.4 Dashboard

新增分頁 `Self Tuning`：
1. **Pending Recommendations**：表格列出每筆 PENDING + confidence 等級色條 + 「APPROVE / REJECT / DEFER」按鈕。
2. **History Timeline**：時間軸顯示已套用的調參，可展開看 before / after report。
3. **Effect Heatmap**：以 `config_key` × 月份 為座標，顯示 effect_status 顏色分布。
4. **Engine Run Log**：每次 cron 跑的紀錄、樣本數、輸出 draft 數、skip 原因。
5. **規則健康度**：每條 rule 的命中次數、approve 率、effect 為 IMPROVED 的比例（方便檢驗 rule 本身是否好）。

**禁止呈現方式**：
- ❌ 不可在主 dashboard 首頁寫「系統已自我優化 X 項」
- ❌ 不可在 LINE「主攻」段呈現 PENDING 建議
- ✅ 只能在 `Self Tuning` 分頁與每日 19:30 一封獨立 LINE「📋 今日待審調參建議 N 筆」

---

## 7. 統計口徑（Definitions）

> 所有 rule 共用同一份口徑；新欄位需先在此定義過才可在 evidence_json 出現。

### 7.1 Decision Performance（per finalDecision in candidate_forward_tracking）
```
sample_n          = COUNT(*) WHERE finalDecision = X AND tradingDate ∈ window AND t5_close_pct IS NOT NULL
win_rate          = SUM(t5_close_pct > 0) / sample_n
avg_t1_close      = AVG(t1_close_return_pct)
avg_t3_close      = AVG(t3_close_return_pct)
avg_t5_close      = AVG(t5_close_return_pct)
avg_t10_close     = AVG(t10_close_return_pct)
avg_mfe           = AVG(mfe_pct)
avg_mae           = AVG(mae_pct)
hit_stop_rate     = SUM(hit_stop=true) / sample_n
hit_target_rate   = SUM(hit_target=true) / sample_n
relative_t5       = avg_t5_close - benchmark_avg_t5  -- benchmark = 0050 同期
```

### 7.2 Strategy Performance（per primary_strategy）
```
與 7.1 相同，加：
strategy_share    = COUNT(*) per strategy / total_candidates
avg_score_at_decision = AVG(final_score)
ENTER_within_strategy = SUM(finalDecision='ENTER') / per strategy
```

### 7.3 Gate Missed Rally（per gate_name in missed_rally_tracking）
```
sample_n          = COUNT(*) WHERE gate_name = G AND original_decision IN ('REJECT','WAIT','WATCH')
missed_rally_rate = SUM(missed_rally_flag=true) / sample_n
avg_t5_max        = AVG(max_return_pct)
avg_t5_close      = AVG(close_return_pct)
would_hit_stop    = SUM(would_have_hit_stop=true) / sample_n
relative_max_t5   = avg_t5_max - benchmark_avg_t5_max
```

### 7.4 Score Buckets（per grade）
```
與 7.1 相同，分組 BY grade（A_PLUS / A / B / WATCH / REJECTED）。
驗證重點：
  avg_t5_close(A_PLUS) > avg_t5_close(A) > avg_t5_close(B)
若不成立，代表評分系統 grade 分層失效；rule engine 須在 evidence_json 標 `grade_layer_broken=true`，且 confidence 自動 -0.2。
```

### 7.5 Before / After（套用後評估）
```
before_window = [appliedAt - 20 trading days, appliedAt - 1]
after_window  = [appliedAt + 1, appliedAt + 20 trading days]
比較欄位：avg_t5_close, win_rate, missed_rally_rate, hit_stop_rate
effect_status:
   IMPROVED  = 後 vs 前 至少 2 項顯著改善（差異超過 1 倍 stdDev）
   WORSE     = 後 vs 前 至少 2 項顯著惡化
   NEUTRAL   = 其餘
若樣本 (after_window) < 20 → effect_status = PENDING_OBSERVATION（不結論）
```

---

## 8. 安全限制與驗收標準（逐條對應原始需求）

| # | 安全限制 | 設計如何保證 | 驗收標準 |
|---|---|---|---|
| 1 | **不自動 apply** | `Engine.run` 只回傳 draft；唯一寫入路徑為 `Service.approveAndApply`，且該方法簽名要求 `userId` + `confirmation=true` | 單元測試：mock `Engine.run` 產 5 筆 draft；驗證 `score_config` 完全不變；只有顯式呼叫 `approveAndApply` 才會改寫 |
| 2 | **不自動下單** | Tuning module 與 `PaperTradeService` / `PositionService` 之間沒有任何寫入呼叫；DI 圖只允許讀 | 整合測試：執行一輪 daily job → 查 `paper_trade` / `position` 行數不變 |
| 3 | **不用單日結果** | 所有 rule 強制 `lookback_days >= 20` 且 sample 取自 `tradingDate ∈ [today-N, today-5]`（保留 T+5 觀察期）| 單元測試：傳 `lookback_days=1` → 直接 throw `IllegalArgumentException` |
| 4 | **不覆蓋 config without rollback** | `approveAndApply` 永遠先寫 `strategy_tuning_history (previousValue)` 再呼叫 `ScoreConfigService.update`；事務內任一失敗則整個 rollback | 整合測試：刻意讓 `ScoreConfigService.update` throw → 驗證 history 也未寫入 |
| 5 | **不把 pending 說成已優化** | UI 只在 `Self Tuning` 分頁顯示 PENDING；首頁 dashboard banner 與 LINE 主訊息區皆不渲染 PENDING；模板 unit test 阻擋此措辭 | 前端 snapshot test：`PENDING` 不出現 `已優化 / 已生效 / improvement_applied` 字樣 |
| 6 | **樣本不足不出建議** | rule 共用 `MIN_SAMPLE = 20` 守門；不足時 confidence_level=`INSUFFICIENT_DATA` 且 `Service.persistDrafts` 過濾掉這類 draft | 單元測試：給 19 筆 sample → drafts_produced=0；給 20 筆且符合條件 → drafts_produced=1 |
| 7 | **必有統計證據** | `evidenceJson` 不為空、必含 sample / mean / stdDev / pValue (or bootstrapCI) / benchmark / rawQuery；DB level NOT NULL | 整合測試：嘗試 insert 空 evidence_json → DB 拒絕 |
| 8 | **必有 confidence** | `final_confidence`、`confidence_level` 為 NOT NULL；API response schema 必含這兩欄；前端 schema 驗證 | 契約測試：`/recommendations` 回傳缺欄位 → 失敗 |
| 9 | **必有 rollback** | `strategy_tuning_history` 必含 `previousValue`；`POST /history/{id}/rollback` 端點存在且 idempotent | E2E：approve → rollback → `score_config` 還原 + 多一筆 type=ROLLBACK history |
| 10 | **必有 history** | 任何 `score_config` 變動皆透過 `Service.approveAndApply` 或 `manualOverride`（亦寫 history type=`MANUAL_OVERRIDE`）| 整合測試：直接呼叫 `ScoreConfigService.update` 不走 service → 寫一個 DB trigger / aspect 自動補 history `MANUAL_OVERRIDE`，並在 daily job 報異常 |
| 11 | **必有 before/after** | `StrategyTuningAfterReportJob` 每日 19:30 對 appliedAt+20 交易日的 history 跑報告；`effect_status` 從 `PENDING_OBSERVATION` 轉為終態 | 整合測試：模擬 20 交易日後資料 → effect_status 寫入正確值 |

### 8.1 額外驗收（與 strategy-design / test-plan 對齊）

- 不影響既有測試：`mvn -q test` 全綠（含 `StrategyGateTests`、`MissedRallyTrackingServiceTests`、`PositionDecisionEngineTests`、`FinalDecisionCandidateRequestTests`）。
- 新增測試類別建議（後續 Phase 實作時補上，本文件不寫測試碼）：
  - `StrategyTuningEngineTests`（每條 rule 的 happy / insufficient / boundary case）
  - `StrategyTuningServiceTests`（approve / reject / rollback / staleBaseline / 衝突群組）
  - `StrategyTuningAfterReportJobTests`（before / after 統計、effect_status 判定）
  - `StrategyTuningControllerTests`（idempotency key、X-Austin-Confirm 缺漏拒絕）

### 8.2 監控（不在本文件範圍但建議）

- Daily job 失敗 → 寫 SYSTEM_ALERT，但**不**自動進建議；只通知人。
- 30 日內 approve_rate < 30% → 表示 rule engine 信號偏弱，由 Austin 決定是否調整 rule 閾值（手動，不自動）。

---

## 9. Phase 實作順序（建議給 Codex）

| Phase | 範圍 | 預估工作量 |
|---|---|---|
| **Phase 0** | DB migration `V29__strategy_tuning.sql`、Entity / Repository | 1 工作日 |
| **Phase 1** | `StrategyTuningEngine` 骨架 + Rule A / B（最常見的兩條）+ confidence 計算 | 2 工作日 |
| **Phase 2** | 完成 Rule C / D / E / F + `StrategyTuningService.persistDrafts` | 2 工作日 |
| **Phase 3** | `approve / reject / rollback` API + idempotency / X-Austin-Confirm | 1.5 工作日 |
| **Phase 4** | `StrategyTuningDailyJob` + Engine run log + 樣本品質過濾 | 1 工作日 |
| **Phase 5** | `StrategyTuningAfterReportJob` + before / after 報告 | 1.5 工作日 |
| **Phase 6** | Dashboard `Self Tuning` 分頁（5 個區塊）+ 一封獨立 LINE 模板 | 2 工作日 |
| **Phase 7** | E2E test：模擬 30 日 tracking 資料 → 觀察 PENDING / APPROVE / 套用 / before-after / rollback 完整路徑 | 1 工作日 |

> 建議在 Phase 1 / Phase 2 完成後就上 PENDING-only 模式跑 30 日，**先不開放 approve**；確認 rule 命中率與 confidence 分布合理後，再開放 Phase 3 的 approve 路徑。

---

## 10. 開放議題（待 Austin 確認）

1. **MIN_SAMPLE 預設 20 是否夠？** Austin 中短線一週只交易 3-5 檔，若 lookback=30 個交易日仍可能達不到 20。是否容忍 `MIN_SAMPLE=15` 但 confidence 上限為 MEDIUM？
2. **PENDING 預設有效 7 天**：是否要區分 aggressive (3 天) vs defensive (14 天)？
3. **是否提供「一鍵全部 reject」**：方便 Austin 旅行回來快速清理。預設不提供，避免誤觸。
4. **rollback 是否要加冷卻**：例：rollback 後同 key 24 小時內不接受新建議，避免 ping-pong。本文件預設加，但閾值待 Austin 確認。
5. **是否允許 multi-key 群組同時 approve**：本文件設計支援 `groupId`（一組一起核准），但仍逐筆寫 history。是否要讓 UI 自動把同組合併呈現？

---

**結束。等 Austin / Codex review。**
