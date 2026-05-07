# 台股 AI 交易系統實戰化升級實作計畫

## 目標

本次升級先修正決策可信度 P0 問題，再加入可被 forward tracking 驗證的三策略基礎模型。所有自動下單仍維持關閉；shadow / observation 僅能以追蹤資料呈現，不得包裝成 live decision。

## Phase 1：P0 可信度修正

1. `FinalDecisionCandidateRequest` 增加安全 copy / wither，`FinalDecisionService.applyScoringPipeline` 重建 candidate 時完整保留 price gate、market regime、execution overlay 與 tradability 欄位。
2. Dashboard 增加明確語意：`marketState`、`monitorState`、`tradeDecision`、`finalDecisionCode`。若 FinalDecision 是 `REST`，`tradeDecision` 必須是 `REST`，不得因市場或 monitor 觀察訊號顯示為 ENTER。
3. 持倉 review 對 quote unavailable / stale / null 改為 `DATA_BLOCKED` 或 `QUOTE_STALE`，不可輸出正常 `HOLD`，且不覆蓋既有 EXIT 等高優先狀態。
4. trailing stop 以未實現損益分段鎖利，且只能上修：5% 鎖成本、10% 鎖成本 +5%、20% 鎖成本 +10%、30% 鎖成本 +20%。報價不可用時不更新。

## Phase 2：三策略模型

新增 `StrategyType`、`StrategyClassifier`、`BreakoutGate`、`PullbackGate`、`ContinuationGate`、`StrategyGateService`。初版只作為 trace / decision 輔助，不宣稱已驗證 live edge；所有新策略欄位需可進入 candidate 並供 tracking 查核。

## Phase 3：Tracking 基礎版

新增 `MISSED_RALLY_TRACKING` 與 `CANDIDATE_FORWARD_TRACKING` entity / repository / service / API / migration。初版提供手動或 job 可填資料的持久化與彙總查詢，後續再接每日收盤價補值。

## Phase 4：Dashboard / Notification 接線

Dashboard 顯示策略分布、候選分布、missed rally 與 forward tracking summary，以及持倉現行 / 建議 stop。通知文案需區分「市場偏強但 final REST」、「breakout 觀察」、「pullback 等回測」、「持倉 stop 上修」。
