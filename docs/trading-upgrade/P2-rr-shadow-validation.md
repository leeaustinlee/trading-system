# P2 RR Shadow Validation Loop

## 目的

P2 將 P1.6 的 RR root-cause diagnosis 推進成可持續累積樣本的 shadow validation loop。

本階段仍然只做診斷與驗證：

- 不改 production BUY path。
- 不新增 auto-order。
- 不觸發真倉 SELL。
- 不把 shadow RR gate 結果寫回正式決策。

## 新增資料表

`rr_shadow_validation`

用途：保存每筆 paper trade 的 RR shadow gate 結果與後續 forward return，讓系統可以回答：若低 RR gate 擋掉這些交易，是少賠、錯過贏家，還是資料不足。

主要欄位：

- `paper_trade_id`
- `trading_date`
- `symbol`
- `strategy_type`
- `entry_price`
- `stop_loss_price`
- `target1_price`
- `target2_price`
- `rr_ratio`
- `shadow_status`：PASS / FAIL / DATA_GAP
- `root_cause_bucket`
- `t1_return_pct` / `t3_return_pct` / `t5_return_pct` / `t10_return_pct`
- `avoided_loser_flag`
- `missed_winner_flag`
- `data_gap_reason`

`paper_trade_id` 有 unique key，manual backfill 可重跑且 idempotent。

## API

### 1. RR shadow validation backfill

```http
POST /api/backtest/diagnosis/rr-shadow-validation/backfill?days=60
```

行為：

1. 讀取指定期間 `paper_trade`。
2. 使用 `RiskRewardShadowGateService` 計算 RR shadow status。
3. 從 `paper_trade.return_1d/3d/5d/10d` 或同日同股 `candidate_forward_tracking` 補 forward return。
4. 寫入 / 更新 `rr_shadow_validation`。
5. 缺資料以 `DATA_GAP` / `dataGapReason` 回報，不補假資料。

### 2. RR shadow validation summary

```http
GET /api/backtest/diagnosis/rr-shadow-validation/summary?days=60
```

回傳重點：

- `totalRows`
- `failedGateRows`
- `dataGapRows`
- `wouldBlockCount`
- `wouldBlockPct`
- `blockedAvgReturnT1/T3/T5/T10`
- `dataGaps`：blocked rows 各 horizon 缺 return 筆數
- `avoidedLoserCount`
- `missedWinnerCount`
- `topRootCauseBuckets`
- `sampleSymbols`
- `blockedReturnCoveragePct`

### 3. RR root cause diagnosis integration

```http
GET /api/backtest/diagnosis/rr-root-cause?days=60
```

若 `rr_shadow_validation` 已有資料，`shadowImpact` 會優先使用 persisted validation summary，否則 fallback 到即時計算。

### 4. General diagnosis integration

```http
GET /api/backtest/diagnosis/recent?days=60
```

新增 `rrShadowValidationStatus`，並把 persisted RR shadow validation coverage 作為 root-cause ranking 補充資訊。

## Forward data coverage

`POST /api/market-index/backfill-symbols` 已強化：

- 支援 `includePaperTrades`
- 支援 `includeCandidates`
- 支援 `maxSymbols`
- 回傳 per-symbol summary
- t00 benchmark 若 TWSE 無資料或回 HTML，標記 `BENCHMARK_DATA_GAP`，不讓個股 backfill 整體失敗
- idempotent：既有日線不重複插入

範例：

```http
POST /api/market-index/backfill-symbols?days=90&includePaperTrades=true&includeCandidates=true&maxSymbols=50
```

## DATA_GAP 解讀

- `DATA_GAP` 代表資料不足，不代表 AI 分數錯，也不代表 RR gate 有效或無效。
- 若 blocked rows 缺 T1/T3/T5/T10 return，`shadowImpact` 不應被用來正式調整 BUY path。
- 缺資料需先用日線 backfill / forward tracking 補足。

## 何時才可考慮 formal gate

至少滿足：

1. blocked sample >= 30。
2. blocked forward return coverage >= 70%。
3. avoidedLoserCount 明顯高於 missedWinnerCount。
4. missedWinnerCount 可控，且錯過贏家不是主流題材 / 強 momentum 股。
5. 經人工確認後，先進入 shadow warning 或 FinalDecision 顯示欄位；不得直接變成 production hard veto。

## Safety boundary

本階段新增的 table、service、API 都是 shadow / diagnosis only。

- 不寫入真倉 position。
- 不自動下單。
- 不自動賣出。
- 不改正式 FinalDecisionEngine BUY path。
- 所有 gate 結果僅供診斷與人工決策參考。
