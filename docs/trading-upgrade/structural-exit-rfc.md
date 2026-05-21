# Structural Exit RFC：從價格停損升級為結構性持倉決策

> For Hermes：本文件是 trading-system 持倉決策層統一與 StructuralExitEngine 的 RFC / OpenSpec 草案。後續若進入實作，請以 shadow-first、manual-confirm-only、不可自動下單/自動賣出為最高原則。

## 1. 背景

目前 trading-system 已經形成兩套哲學不同的持倉判斷線：

```text
舊世代：price trigger system
FinalDecisionEngine
  -> PositionReviewService
  -> PositionDecisionEngine
  -> Telegram Alert

新世代：structure-aware system
PortfolioHealthV2Service
  -> PositionHealthEngine
```

舊線以價格事件為主：

- 跌破 stop loss
- 跌破 trailing stop
- intraday weakness
- day high drawdown
- below open / below previous close / below VWAP

新線以結構事件為主：

- MA5 / MA10 / MA20 structure
- previous low 是否有效跌破
- volume structure
- relative strength
- mainstream theme / theme stage
- chip status
- health score

近期實際案例顯示，舊線容易在台股主流股的正常洗盤、假跌破、漲停後震盪、回測均線時輸出 WEAKEN / EXIT 類訊號；但 health-v2 同時可能判斷 HOLD。這代表系統的進場 AI 化已經較成熟，但持倉決策仍殘留短線價格停損架構。

核心問題不是「再加更多 indicator」，而是「統一持倉決策層」：把出場從價格事件升級成結構事件。

## 2. 核心診斷

### 2.1 真正會吃掉波段績效的問題

目前最容易虧掉的不是選錯股票，而是在最該抱的地方被洗掉。

台股主流股常見節奏：

```text
爆量啟動 -> 洗盤 / 換手 -> 再攻 -> 加速
```

如果系統把「洗盤」等同「趨勢壞」，就很難抱到主升段。

### 2.2 具體 code path 問題

目前舊線在以下位置仍偏價格觸發：

- `src/main/java/com/austin/trading/service/PositionReviewService.java`
  - `evaluatePosition(...)` 使用 live quote currentPrice/dayHigh/dayLow/prevClose。
  - `sessionHigh` 目前直接用 dayHigh 近似，不是持倉期間高點或收盤 swing high。
  - `marketGrade` 固定 B。
  - `themeRank` / `finalThemeScore` 多數為 null。
  - `volumeWeakening=false`、`failedBreakout=false`、`extendedLevel=NONE`。
  - `momentumStrong` 簡化為持有 <=3 天或 currentPrice > avgCost。

- `src/main/java/com/austin/trading/engine/PositionDecisionEngine.java`
  - `currentPrice <= effectiveStop` 直接觸發 stop / trailing stop 判斷。
  - `pnlPct <= -6%` 直接 EXIT。
  - drawdown 從 dayHigh 回撤判斷 WEAKEN / EXIT。
  - trailing stop 雖已有 `position.review.trailing_stop_requires_structure=true` 保護，但只有 trailing stop 分支具備結構確認；若 effective stop 由 hard stop 觸發，仍偏價格事件。

- `src/main/java/com/austin/trading/scheduler/FiveMinuteMonitorJob.java`
  - 每 5 分鐘呼叫 `positionReviewService.reviewAllOpenPositions("INTRADAY")`。
  - WEAKEN / EXIT / TRAIL_UP 會進 notification path。

- `src/main/java/com/austin/trading/service/PortfolioHealthV2Service.java`
  - health-v2 輸出 `SHADOW_MANUAL_CONFIRM_ONLY`，目前仍是 shadow/manual confirm。
  - 但其資料哲學更接近波段持倉：MA、量、RS、題材、籌碼。

- `src/main/java/com/austin/trading/engine/PositionHealthEngine.java`
  - 已有結構雛形：BULL_ALIGNED、MA10_BREAK、PREVIOUS_LOW_BREAK、LOW_VOLUME_PULLBACK、RISING_VOLUME、OUTPERFORM。
  - 目前尚未成為正式 arbiter。

### 2.3 Evidence：exit quality 已證明現行 stop 偏早

`/api/backtest/diagnosis/exit-rule-cases?days=60` 已顯示：

- totalTrades = 12
- CURRENT_RULE_OK = 6
- EXIT_TOO_EARLY = 6
- positiveAlternativeDeltaCases = 7
- avgPositiveAlternativeDeltaPct = 7.5942%
- bestAlternativeRule：MA5 = 11、MA10 = 1

這不是主觀感覺，而是系統已有 evidence：現行 stop / TP / exit timing 對波段偏早。

## 3. 設計目標

### 3.1 不做的事

- 不新增自動下單。
- 不新增自動賣出。
- 不直接放寬所有停損。
- 不讓 shadow / paper 結果偽裝成 live BUY/SELL。
- 不用更多 indicator 堆疊來掩蓋架構問題。

### 3.2 要做的事

1. 統一持倉決策層。
2. 將 health-v2 / PositionHealthEngine 升級為正式持倉警報 arbiter。
3. 新增 StructuralExitEngine，將出場從價格事件升級成結構事件。
4. 新增 Leader Stock Mode，使主流 leader 不再與普通股共用同一套 stop。
5. 新增 close confirm / T+1 confirm / observation mode。
6. 先 shadow validation，再調整 live alert wording，再逐步升級正式 review semantics。

## 4. Leader Stock Mode

Leader Stock Mode 比一般 washout detection 更優先。

原因：主流 leader 與普通股的 stop 哲學不同。

普通股跌破 5MA：可能真的弱。

主流 leader 跌破 5MA：可能只是洗融資、洗隔日沖、洗短線籌碼、洗前高套牢。

### 4.1 Leader 判斷條件

可用以下訊號推導 `leaderStockMode=true`：

- mainstreamTheme = true
- themeStage = ACTIVE / ACCELERATING
- relativeStrengthStatus = OUTPERFORM
- healthScore >= 70
- structureStatus 非 PREVIOUS_LOW_BREAK / MA10_BREAK 加劇
- chipStatus = BULLISH 或至少非 BEARISH
- 最近 5 日或 10 日強於大盤 / 同族群
- Codex / Claude / theme trace 沒有強烈否定
- 成交量仍維持活絡，非流動性消失

### 4.2 Leader Stop Policy

Leader 不應以 intraday 觸價作為 EXIT。

建議：

- 盤中跌破 trailing stop：OBSERVE_1D，不 EXIT。
- 盤中跌破 5MA：SOFT_WARNING，不 EXIT。
- 收盤跌破 5MA 但 10MA 未破：REDUCE_REVIEW 或 OBSERVE_1D。
- 收盤跌破 10MA + 放量 + RS 轉弱：EXIT_REVIEW。
- 跌破前低 + 題材轉弱 + 放量長黑：HARD_EXIT_ALERT。
- 長下影 / 假跌破 / 開低走高：WASHOUT_HOLD。

## 5. StructuralExitEngine 規格

### 5.1 新類別

建議新增：

```text
src/main/java/com/austin/trading/engine/StructuralExitEngine.java
src/main/java/com/austin/trading/dto/StructuralExitInput.java
src/main/java/com/austin/trading/dto/StructuralExitResult.java
src/main/java/com/austin/trading/domain/enums/StructuralExitTier.java
src/main/java/com/austin/trading/domain/enums/PositionMode.java
```

### 5.2 Input

`StructuralExitInput` 應包含：

```text
symbol
positionId
strategyType
positionMode
avgCost
currentPrice
closePrice
openPrice
prevClose
intradayHigh
intradayLow
stopLossPrice
trailingStopPrice
takeProfit1
takeProfit2
unrealizedPnlPct
holdingDays
ma5
ma5Previous
ma10
ma20
previousLow
recentHigh
atr
volumeRatio
stockReturn5d
benchmarkReturn5d
relativeStrengthStatus
structureStatus
volumeStatus
chipStatus
mainstreamTheme
themeStage
healthScore
oldReviewStatus
oldReviewReason
```

### 5.3 Output

`StructuralExitResult`：

```text
symbol
positionId
oldDecision
structuralTier
recommendedAction
manualConfirmRequired
autoSellEnabled=false
leaderStockMode
washoutDetected
closeConfirmRequired
nextSessionConfirmRequired
reduceSuggestedPct
reasonCodes
humanReadableReason
```

`StructuralExitTier`：

```text
HOLD
SOFT_WARNING
OBSERVE_1D
REDUCE_REVIEW
EXIT_REVIEW
HARD_EXIT_ALERT
WASHOUT_HOLD
RE_ENTRY_ALLOWED
DATA_BLOCKED
```

### 5.4 Decision Rule

核心規則：

```text
if dataBlocked:
    DATA_BLOCKED

if hardStopByCloseAndStructureBroken:
    HARD_EXIT_ALERT

if leaderStockMode and intradayBreakOnly and structureNotBroken:
    OBSERVE_1D

if washoutDetected:
    WASHOUT_HOLD

if closeBreakMa10OrPreviousLow and volumeBreakdown and rsWeak:
    EXIT_REVIEW

if closeBreakMa5 but ma10Hold and themeActive:
    REDUCE_REVIEW or OBSERVE_1D

if trailingStopBreakOnly and healthScore >= 70:
    OBSERVE_1D

if healthScore >= 70 and rsOutperform and themeActive:
    HOLD
```

## 6. Alert wording 改造

Phase A 最重要，且風險最低。

舊 wording 不應再直接輸出：

```text
⚠️ EXIT
```

在 old review = EXIT / WEAKEN，但 structural / health-v2 尚未確認結構破壞時，應改成：

```text
⚠️ 結構觀察中
跌破移動停利，但趨勢未確認破壞。
主流股可能屬於 washout / 換手，收盤前不建議盤中全出。
```

或：

```text
⚠️ 跌破移動停利，人工確認
health-v2 仍為 HOLD：相對強勢 / 題材 / 量能尚未轉壞。
建議：觀察收盤是否跌破 10MA / 前低；未確認前不全出。
```

## 7. Close Confirm / T+1 Confirm

### 7.1 Intraday break

盤中跌破：

- stop loss
- trailing stop
- MA5
- VWAP
- previous low

不直接 EXIT。

輸出：

```text
SOFT_WARNING 或 OBSERVE_1D
```

### 7.2 Close break

收盤跌破才升級：

- 收盤跌破 5MA：REDUCE_REVIEW / OBSERVE_1D
- 收盤跌破 10MA：EXIT_REVIEW
- 收盤跌破 previous low 且放量：HARD_EXIT_ALERT

### 7.3 T+1 confirm

若當日出現假跌破 / 長下影 / washout 訊號：

- T+1 站回 VWAP / 5MA：HOLD / RE_ENTRY_ALLOWED
- T+1 再破低：EXIT_REVIEW

## 8. WashoutDetectionEngine 規格

WashoutDetectionEngine 是 Phase C，不是 Phase A。

建議新增：

```text
src/main/java/com/austin/trading/engine/WashoutDetectionEngine.java
```

偵測型態：

1. 長下影洗盤
   - intradayLow 跌破 stop / MA5 / previousLow
   - close 收上半
   - lowerShadowRatio >= 40%
   - volumeRatio >= 1.3 或正常

2. 回踩 5MA 不破
   - low <= MA5
   - close >= MA5
   - volumeRatio <= 1.2

3. 回測 10MA 縮量
   - low <= MA10
   - close >= MA10 或接近 MA10
   - volumeRatio <= 0.8

4. 假跌破前低
   - low < previousLow
   - close > previousLow

5. 開低走高
   - open < prevClose
   - close > open
   - close > VWAP 或 close location 高

6. 漲停後震盪洗盤
   - 前一日強攻 / 漲停
   - 當日高震盪但未放量崩跌
   - 收盤維持在前一日 K 棒中上緣

## 9. Migration Strategy

### Phase A：立即，通知與 arbiter 統一

不改交易邏輯，只改決策語意與正式警報來源。

任務：

1. health-v2 成為正式持倉警報 arbiter。
2. 舊 PositionReviewService 結果降級為 raw signal。
3. Telegram / NotificationFacade 改成讀 arbiter result。
4. 若 old=EXIT 但 health-v2=HOLD，通知顯示「結構觀察中」，不可顯示「EXIT」。
5. DATA_BLOCKED / currentPrice=null 不可產生 EXIT 類訊號。
6. 加 1582 信錦 regression test。

驗收：

- 1582 類情境：trailing stop break + healthScore >= 70 + RS OUTPERFORM + mainstreamTheme=true -> OBSERVE_1D / HOLD，不得 EXIT。
- health-v2 HOLD 時，Telegram 不得顯示 EXIT。
- autoSellEnabled 仍為 false。

### Phase B：StructuralExitEngine shadow

任務：

1. 新增 StructuralExitEngine。
2. 新增 shadow log table 或複用 existing shadow_exit_comparison 擴欄。
3. 每次 PositionReviewService 產生 review 時，同時計算 structural result。
4. `/api/portfolio/health-v2` 增加 structural fields。

驗收：

- oldDecision 與 structuralDecision 同時可查。
- 不改 live sell / close path。
- 可統計 disagreement cases。

### Phase C：WashoutDetectionEngine

任務：

1. 新增 washout detection。
2. 新增 washout reason codes。
3. 和 StructuralExitEngine 整合。
4. 加台股案例測試：長下影、假跌破、開低走高、漲停後洗盤。

### Phase D：Forward validation

至少累積：

- 30 筆 exit / weaken alerts
- 20 筆 washout signals
- T+1 / T+3 / T+5 return
- false exit rate
- avoided false exit delta
- maximum adverse excursion

通過後再考慮讓 structural result 影響正式 reviewStatus。

## 10. Backtest / Forward Metrics

### 10.1 Backtest

比較規則：

- CURRENT
- MA5 close confirm
- MA10 close confirm
- ATR 2x
- previous low close confirm
- trailing stop intraday
- trailing stop close confirm
- structural stop
- washout-aware stop

指標：

- total return
- max drawdown
- win rate
- average hold days
- false exit rate
- missed rally rate
- T+1/T+3/T+5 after exit
- average positive alternative delta

### 10.2 Forward Paper Validation

每筆持倉每日記錄：

```text
symbol
positionId
oldReviewDecision
healthV2Tier
structuralTier
washoutDetected
humanAction
next1dReturn
next3dReturn
next5dReturn
outcomeLabel
```

Outcome label：

```text
OLD_CORRECT_EXIT
OLD_FALSE_EXIT
STRUCTURAL_CORRECT_HOLD
STRUCTURAL_TOO_SLOW
DATA_GAP
```

## 11. ROI Priority

P0：最高 ROI，立即做

1. Alert wording 改造：不要把未確認結構破壞顯示成 EXIT。
2. health-v2 成為正式持倉警報 arbiter。
3. trailing stop break + healthScore >= 70 -> OBSERVE_1D，不得 EXIT。
4. DATA_BLOCKED 不得產生 EXIT。
5. 1582 信錦 regression test。

P1：核心升級

6. StructuralExitEngine shadow。
7. `/api/portfolio/health-v2` 增加 structural fields。
8. close confirm / T+1 confirm。
9. Leader Stock Mode。

P2：台股化進階

10. WashoutDetectionEngine。
11. PositionType / StopPolicy。
12. TP1/TP2 波段化。
13. Candidate T+5 watchlist 與 re-entry allowed。

## 12. Implementation Tasks

### Task 1：新增 structural tier enum 與 DTO

Files：

- Create: `src/main/java/com/austin/trading/domain/enums/StructuralExitTier.java`
- Create: `src/main/java/com/austin/trading/domain/enums/PositionMode.java`
- Create: `src/main/java/com/austin/trading/dto/StructuralExitInput.java`
- Create: `src/main/java/com/austin/trading/dto/StructuralExitResult.java`

驗收：

```bash
mvn -q -DskipTests compile
```

### Task 2：新增 StructuralExitEngine 初版

Files：

- Create: `src/main/java/com/austin/trading/engine/StructuralExitEngine.java`
- Test: `src/test/java/com/austin/trading/engine/StructuralExitEngineTests.java`

必測案例：

1. trailing stop break + healthScore 80 + RS OUTPERFORM + mainstreamTheme=true -> OBSERVE_1D。
2. currentPrice null -> DATA_BLOCKED。
3. close below MA10 + volume breakdown + RS UNDERPERFORM -> EXIT_REVIEW。
4. previous low break + high volume + healthScore < 25 -> HARD_EXIT_ALERT。
5. leaderStockMode true + intraday 5MA break only -> OBSERVE_1D。

### Task 3：health-v2 整合 structural result

Files：

- Modify: `PortfolioHealthV2Service.java`
- Test: `PortfolioHealthV2ServiceTests.java` 或新增對應測試

新增欄位：

```text
structuralTier
structuralReason
leaderStockMode
closeConfirmRequired
washoutDetected
oldReviewStatus
```

### Task 4：Notification wording 改造

Files：

- Modify: `TelegramTemplateService.java`
- Modify: `NotificationFacade.java` if needed
- Test: `TelegramTemplateServiceFormattingTests.java`

驗收：

- health-v2 HOLD + old review WEAKEN/EXIT 時，不得輸出 EXIT。
- 顯示「結構觀察中 / 跌破移動停利但未確認破壞」。

### Task 5：PositionReviewService raw signal 降級

Files：

- Modify: `PositionReviewService.java`
- Test: `PositionReviewServiceTests.java`

原則：

- 保留 position_review_log。
- 不讓 raw EXIT 直接成為正式 user-facing exit wording。
- autoClose 仍 disabled / paper-only。

### Task 6：Forward validation log

Files：

- Create SQL migration：`sql/V*_structural_exit_shadow_log.sql`
- Create entity/repository/service/controller as needed

Endpoint：

```text
GET /api/portfolio/structural-exit-shadow?days=30
GET /api/portfolio/structural-exit-shadow/summary?days=30
```

## 13. Safety Requirements

所有階段都必須滿足：

- `autoBuyEnabled=false`
- `autoSellEnabled=false`
- StructuralExitEngine 不可呼叫 PositionService.close。
- 不可改變實際下單 / 出場 side effect。
- 所有 live 行為先限於 notification wording / shadow logging / manual confirm。
- DB/API 欄位必須清楚標示 shadow / manual-confirm-only。

## 14. Acceptance Criteria

P0 完成後，以下情境必須成立：

### 1582 信錦 regression

Input：

```text
symbol=1582
currentPrice=117.5
trailingStop=117.98
avgCost=107.25
healthScore=80
structureStatus=NEUTRAL
volumeStatus=RISING_VOLUME
relativeStrengthStatus=OUTPERFORM
chipStatus=BULLISH
mainstreamTheme=true
```

Expected：

```text
structuralTier=OBSERVE_1D 或 HOLD
recommendedAction=不建議盤中全出
manualConfirmRequired=true
autoSellEnabled=false
Telegram 不得顯示 EXIT
```

### Hard stop regression

Input：

```text
close < previousLow
volumeRatio >= 1.8
healthScore < 25
relativeStrengthStatus=UNDERPERFORM
themeStage=COOLING/DECAY
```

Expected：

```text
structuralTier=HARD_EXIT_ALERT
manualConfirmRequired=true
autoSellEnabled=false
```

## 15. One-line Principle

```text
if price_broken but structure_intact: tolerate_washout;
if structure_broken: exit_review;
if leader_stock and trend_intact: hold_main_wave.
```
