# 測試計畫

## 必跑命令

```bash
mvn -q -DskipTests compile
mvn -q test
```

若全量測試時間不足，優先執行：

```bash
mvn -q -Dtest=FinalDecisionCandidateRequestTests,PositionDecisionEngineTests,StrategyGateTests,MissedRallyTrackingServiceTests test
```

## P0 測試重點

- `FinalDecisionCandidateRequest.copyWithScores` 重建後不丟 `currentPrice/openPrice/previousClose/dayHigh/dayLow/vwap/volumeRatio/marketRegime/belowOpen/belowPrevClose/nearDayHigh/tradabilityTag`。
- PriceGate trace 與實際 gate 使用同一份 candidate price 欄位。
- quote available 時正常 review；quote unavailable/null/stale 時輸出 `DATA_BLOCKED` 或 `QUOTE_STALE`，不可是正常 `HOLD`。
- `EXIT` 不被低優先狀態覆蓋。
- trailing stop 在 +10/+20/+30 情境上修，且現有較高 stop 不被下修；quote stale 不更新。

## 三策略測試重點

- `StrategyClassifier` 能分類 breakout / pullback / momentum continuation。
- `BreakoutGate` 不因 `nearDayHigh=true` 直接 reject。
- `PullbackGate` 要求 RR 與支撐 / entry zone，不合格 reject。
- `ContinuationGate` 接受中等 RR，過熱或爆大量長黑 reject。

## Tracking 測試重點

- `MissedRallyTrackingService` 對 T+5 max return >= 8% 標記 `missedRallyFlag=true`。
- API summary / by-gate / by-strategy 可回傳空集合與既有資料彙總，不破壞 DB。
