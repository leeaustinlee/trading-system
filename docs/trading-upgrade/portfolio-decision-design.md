# 台股 AI 交易系統｜Portfolio Decision 設計

> 版本：portfolio-decision-design v1（2026-05-07）
> 範圍：在 `strategy-design.md`（三策略 + tracking）/ `self-tuning-design.md`（PENDING 建議 + 人工 approve）/ `after-tuning-validation-design.md`（before/after 驗證）已奠定的「選股 → 評分 → 決策 → 追蹤 → 自動調參 → 驗證」閉環之上，補上「**對既有持倉做每日健檢、產出換股建議、輸出明日策略**」這一決策層。
> 定位：**決策輔助 + 紀錄 + 通知**。本系統 **不下單**、**不自動換股**、**不自動加碼**、**不自動平倉真倉**、**不依單檔即時跳動改變持倉狀態**、**所有換股 / 加碼 / 出場建議皆需 Austin 明確執行**。
> 與既有文件關係：
> - **不取代** `strategy-design.md §4`（持倉 trailing stop 五段鎖利）；本文件擴充其上，把「個股級健檢」與「組合級換股決策」分層。
> - **不取代** `PositionDecisionEngine` / `PositionReviewService`；本文件新增 `PositionIntelligenceEngine` 在其上層，**讀取**他們的 raw status 與 trailing 資料，產出更高層次的 `holdQuality` 評估。
> - **不取代** `FinalDecisionEngine`；本文件 `PortfolioSwitchAnalyzer` 只比較「目前持倉 vs 今日 / 明日候選」，不重新跑 candidate 篩選，候選清單由現有 FinalDecision pipeline 提供。

---

## 0. 設計總原則（Hard Rules，破壞任一條即視為設計失敗）

1. **不自動下單**：本層所有輸出皆為 LINE / Dashboard 文字訊號（HOLD / REDUCE / EXIT / SWITCH / PARTIAL_SWITCH / KEEP），絕對不呼叫 broker API、不寫 `position` 真倉、不會自動觸發 `PositionService.close`。
2. **不自動加碼**：即使候選分數遠高於現有持倉、即使 `pullback / breakout` 訊號完美，系統只能輸出「建議加碼」訊號，不可自動寫 `position.shares` 增量。
3. **不自動換股**：`SWITCH` 是建議；實際出場與進場皆由 Austin 手動執行；系統最多透過 `paper_trade` 模擬該動作。
4. **不依單一即時 tick 改變 holdQuality**：所有評估必須以「至少 5 分鐘聚合資料」+「日線結構」為基礎；不可因盤中一根 1 分 K 跳動，把 `HIGH_HOLD` 翻成 `EXIT`。狀態升降需通過 §1.5 的 sticky / 連續 N 輪 確認規則。
5. **不准把建議呈現為已執行**：UI / LINE 不得顯示「系統已換股 / 已加碼 / 已減碼」字樣；只能用「建議」「待 Austin 執行」等中性描述。
6. **不准用候選分數覆寫持倉真倉狀態**：`PortfolioSwitchAnalyzer` 比較邏輯產出的是「人工決策提示」，不會回寫 `position` entity 的任何欄位（exit_signal_at / trailing_stop_price 等仍由 `PositionReviewService` 負責）。
7. **EXIT 訊號絕對優先**：個股健檢結果為 `EXIT` 時，**換股邏輯不得**把它降回 `KEEP`；換股結論最多附註「先出，再決定買哪檔」。
8. **trailing stop 只可上修不可下修**：本層不重算 trailing；沿用 `strategy-design.md §4.1` 五段；本層只負責**讀**並把它包進 `holdQuality` 與「明日策略」輸出。
9. **與真倉 / paper 隔離**：`paper_trade.is_shadow = true` 的標的不進入本層的 `holdQuality` 評估；本層只看 `position` 真倉。換股建議若被 Austin 執行為 paper，仍走 `PaperTradeService` 既有路徑。
10. **沒有持倉時也要輸出**：明日策略即使「無持倉 + REST」，仍要輸出「為什麼空手 / 三策略當下狀態 / 明日要看什麼」；不得靜默。
11. **手動 override 必留軌跡**：Austin 透過 dashboard / API 修改 `holdQuality` 或忽略換股建議時，必須寫 `position_daily_review.user_override_*` 欄位，可審計。

---

## 1. 持倉健檢評估模型

### 1.1 三層輸出

| 層 | 欄位 | 值域 | 用途 |
|---|---|---|---|
| 強弱 | `strength` | `STRONG` / `NEUTRAL` / `WEAK` | 個股當前型態相對自身過去 + 相對族群 |
| 風險 | `risk` | `LOW` / `MEDIUM` / `HIGH` | 個股當前下檔風險 |
| 持有品質 | `holdQuality` | `HIGH_HOLD` / `HOLD` / `REDUCE` / `EXIT` | 對 Austin 的最終建議（給人看） |

> `holdQuality` 是 Austin 唯一需要看的欄位；`strength` 與 `risk` 是它的支撐證據，UI 可展開查看細節。

### 1.2 強弱（strength）評估欄位

> 全部欄位來自 `quote` / `indicator_daily` / `position` / `theme_snapshot`，由 `PositionIntelligenceEngine` 每日 13:35（與 `ObservationDailyMtmJob` 同框）+ 盤中每 5 分鐘整點觸發；**不**由 1 分 K 觸發。

| 欄位 | 計算來源 | strength +N | 觸發條件 |
|---|---|---:|---|
| `inMainUptrend` | `currentPrice > 5MA > 20MA > 60MA` 且 `5MA` 升角 ≥ 0 | +2 | 主升段確認 |
| `aboveFiveMa` | `currentPrice ≥ 5MA × 0.985`（容忍 1.5% buffer） | +1 | 短線守 5MA |
| `aboveTenMa` | `currentPrice ≥ 10MA × 0.985` | +1 | 中短線守 10MA |
| `aboveSwingLow` | `currentPrice > swingLow(5)` | +1 | 不破前低 |
| `relativeStrengthVsBenchmark` | `(stock_return_5d - benchmark_return_5d) ≥ +1.5pp`（benchmark = 0050） | +1 | 相對大盤強 |
| `volumeShrinkOnPullback` | 回測天數 ≥ 2 且回測量縮 < 0.7x 5MA 量 | +1 | 健康量縮（Pullback 訊號） |
| `volumeExpansionOnRise` | 上漲日量增 ≥ 1.5x 5MA 量 | +1 | 帶量上攻 |
| `themeStillTop` | `theme_snapshot.themeRank ≤ 3` 且 `final_theme_score ≥ 7.0` | +1 | 題材仍熱 |
| `belowFiveMaConsecutive` | 連 2 個交易日收 < 5MA × 0.985 | -2 | 跌破 5MA |
| `belowTenMaConsecutive` | 連 2 個交易日收 < 10MA × 0.985 | -3 | 跌破 10MA（中線轉弱）|
| `breakSwingLow` | `currentPrice < swingLow(5)` | -3 | 破前低 |
| `relativeWeaknessVsBenchmark` | `(stock_return_5d - benchmark_return_5d) ≤ -2.0pp` | -2 | 相對大盤弱 |
| `volumeShrinkOnRise` | 上漲日量縮 < 0.6x 5MA 量 | -1 | 量價背離（弱勢上漲） |
| `themeFading` | `themeRank` 從 ≤ 3 掉到 > 5 | -2 | 題材退潮 |

**分級對應**：

```
strengthScore = sum(以上欄位 +N / -N，clamp 到 [-10, +10])

IF strengthScore ≥ +4    → STRONG
IF strengthScore ≤ -3    → WEAK
ELSE                     → NEUTRAL
```

### 1.3 風險（risk）評估欄位

| 欄位 | 計算來源 | risk +N | 觸發條件 |
|---|---|---:|---|
| `pnlBelowMinusFour` | `unrealizedPnlPct ≤ -4%` 且未觸 stop | +2 | 浮虧接近停損 |
| `volumeSpikeLongBlack` | 當日量 ≥ 1.5x 5MA 量 且 `bodyRatio ≤ -3%` | +3 | 爆量長黑（hard 紅旗） |
| `priceFarAboveFiveMa` | `currentPrice ≥ 5MA × 1.10` | +2 | 過度上漲（拉回機率高） |
| `priceFarAboveTenMa` | `currentPrice ≥ 10MA × 1.18` | +2 | 與 10MA 乖離 |
| `nearResistance` | 距前波壓力 ≤ 1.5%（`prior_swing_high(60)`） | +1 | 壓力區 |
| `bearishDivergence` | RSI(14) 創新低但 price 創新高（或反向） | +2 | 量價 / 動能背離 |
| `marketGradeC` | `market_state.grade = C` 連續 ≥ 2 個交易日 | +2 | 大盤紅燈 |
| `themeFading` | 同 §1.2 | +1 | 題材退潮疊加風險 |
| `consecutiveLossDays` | 連 3 個交易日收黑 | +1 | 連跌 |
| `isInExitSticky` | `exit_signal_at` 在 24 小時內 | +3 | 已發過 EXIT |
| `pnlAbovePlusTwenty` | `unrealizedPnlPct ≥ +20%` 且 `priceFarAboveTenMa = true` | +1 | 高獲利但乖離大（拉回風險） |
| `holdingDaysOverPolicy` | 超過策略 `max_holding_days` | +1 | 持有過久 |

**分級對應**：

```
riskScore = sum(以上欄位 +N，clamp 到 [0, +12])

IF riskScore ≥ 6   → HIGH
IF riskScore ≥ 3   → MEDIUM
ELSE               → LOW
```

> 注意：`risk` 不會被 strength 抵銷；high risk 即使 strength 也 STRONG，仍是 high risk（用於 §1.4 推導 `REDUCE`）。

### 1.4 holdQuality 推導表

| strength | risk | holdQuality | 說明 |
|---|---|---|---|
| STRONG | LOW | `HIGH_HOLD` | 強勢低風險：續抱 + 移動停利可放寬 |
| STRONG | MEDIUM | `HOLD` | 強但有過熱跡象：續抱不加碼 |
| STRONG | HIGH | `REDUCE` | 強但風險已高：建議分批減碼鎖利 |
| NEUTRAL | LOW | `HOLD` | 中性低風險：續抱 |
| NEUTRAL | MEDIUM | `HOLD` | 中性中風險：續抱但縮 trailing buffer |
| NEUTRAL | HIGH | `REDUCE` | 中性高風險：先減後再評估 |
| WEAK | LOW | `REDUCE` | 弱勢但風險未顯：分批出 |
| WEAK | MEDIUM | `EXIT` | 弱勢中風險：建議全出 |
| WEAK | HIGH | `EXIT` | 弱勢高風險：必須全出（最高優先級）|

**強制升級到 EXIT**（不論 strength / risk 表）：
- `breakSwingLow = true` 且 `belowFiveMaConsecutive = true` → 直接 `EXIT`
- `volumeSpikeLongBlack = true` 且 `unrealizedPnlPct < +5%` → 直接 `EXIT`
- `PositionDecisionEngine` 已發 `exit_signal_at` 且尚在 24 小時 sticky 內 → 直接 `EXIT`（與 `strategy-design.md §4.5` sticky EXIT 一致）

**強制降級到 REDUCE**：
- `pnlAbovePlusTwenty = true` 且 `priceFarAboveTenMa = true` → 至少 `REDUCE`（鎖利優先）

### 1.5 狀態 stickiness 與防抖

> 避免「盤中 5 分鐘 K 一震，holdQuality 在 HIGH_HOLD 與 EXIT 間跳動」。

| 變化方向 | 規則 |
|---|---|
| `EXIT` 升級為其他 | 24 小時 sticky；除非 Austin 透過 `clear-exit-sticky` 解除（沿用 `strategy-design.md §4.5`） |
| `HIGH_HOLD` 降級為 `HOLD` 或更差 | 立即生效（風險升高不延遲）|
| `HOLD` 降級為 `REDUCE` | 立即生效 |
| `REDUCE` 升級回 `HOLD` | 需連續 3 輪健檢都評為 `HOLD` 才回升（至少橫跨 1 個交易日的盤後 + 隔日盤前） |
| `HOLD` 升級為 `HIGH_HOLD` | 需連續 3 輪 `STRONG + LOW` 才升級 |

**重評頻率**：
- 盤後 13:35（與 ObservationDailyMtmJob 同框）：完整重評，寫 `position_daily_review`
- 盤中整點（10:00 / 11:00 / 13:00）：盤中即時版本，**不寫 daily_review**，只更新 in-memory cache 並可發 LINE 警示
- 5 分鐘 monitor：只能對 `risk` 部分指標增量更新（如 `volumeSpikeLongBlack`）；**不可**單獨改寫 `holdQuality`

### 1.6 quote stale / null 處理

沿用 `strategy-design.md §4.4` 規則：

| 情境 | 行為 |
|---|---|
| `quote == null` | `holdQuality` 維持上一輪結果，標 `stale_quote = true` |
| quote 過期（last_update > 10 min） | 同上 |
| MA / swingLow 缺資 | 對應 indicator 不參與 strength / risk 計算，標 `partial_data = true` |
| 連續 ≥ 3 輪都 stale | 寫 SYSTEM_ALERT；但**不**自動降級為 EXIT（資料缺失不能等於賣出訊號） |

---

## 2. 停利 / 停損策略

> 本層**不重算** trailing 五段（`UNLOCKED` / `BREAKEVEN` / `LOCK_5PCT` / `LOCK_10PCT` / `LOCK_20PCT`），這由 `PositionDecisionEngine` 沿用 `strategy-design.md §4.1` 處理。本層負責「動態建議停損 / 停利價」並把它**對人**呈現，且根據 holdQuality 微調。

### 2.1 動態 suggested stop loss

每筆 daily review 寫入：

```
suggestedStop = max(
    positionDecisionEngineTrailing,    -- §4.1 五段 trailing 的當前 stop
    suggestedStopByHoldQuality          -- 本層根據 holdQuality 給的下限
)
```

`suggestedStopByHoldQuality` 規則：

| holdQuality | suggestedStopByHoldQuality |
|---|---|
| `HIGH_HOLD` | 採 `5MA × 0.985`（最寬鬆，給趨勢呼吸） |
| `HOLD` | 採 `max(5MA × 0.985, swingLow(5) × 0.99)` |
| `REDUCE` | 採 `max(currentPrice × 0.985, swingLow(3) × 0.995)`（縮緊） |
| `EXIT` | suggestedStop = 「**建議今日盤中出場，不再用 trailing**」（特殊文字旗標） |

**唯一性與 monotonic**：與 §4.1 保持一致 — `suggestedStop` 在持倉期間只可上修不可下修。本層輸出時，若 `suggestedStopByHoldQuality < lastSuggestedStop`，使用 `lastSuggestedStop`；只在 `holdQuality = EXIT` 時例外（用文字旗標）。

### 2.2 suggested take profit

> 沿用 `strategy-design.md §4.3` 的四段 TP，但允許**個股策略類型**有差異。

| 策略類型（從 `position.strategy_type`） | TP1 | TP2 | TP3 | TP4 |
|---|---|---|---|---|
| `BREAKOUT` | +6% | +13% | +25% | +40% |
| `PULLBACK` | +8% | +15% | +25% | +40% |
| `MOMENTUM_CONT` | +6% | +12% | — | — |
| `SETUP`（legacy） | +6% | +12% | +25% | +40% |

**HighQ holdQuality 動態調整**：
- 當 `holdQuality = HIGH_HOLD` 且 `unrealizedPnlPct ≥ TP2`：建議**只賣 1/4 而非 1/3**，剩餘部位放寬 trailing（讓利潤跑） — 系統只輸出建議，不寫 paper_trade 倉位變化。
- 當 `holdQuality = REDUCE`：TP1 已達 → 建議**直接賣 1/2**（而非 1/3），加速降低部位。

### 2.3 trailing stop 只可上修不可下修

**Hard rule**（與 `strategy-design.md §4` 完全一致）：

```
nextSuggestedStop = max(
    lastSuggestedStop,
    candidateStops[]   -- 5MA、10MA、ATR、swingLow 各自算一個
)
```

- 任何時點若 `currentPrice` 跌破 `lastSuggestedStop` → 由 `PositionDecisionEngine` 觸發 `EXIT`，本層 `holdQuality` 應已是 `EXIT`。
- 5MA / 10MA / swingLow 在洗盤中可能下移：本層**不**因 indicator 下移而下調 `suggestedStop`；只取 max。
- TP 達標後 trailing 同步上抬（§4.3 規則）：本層只**讀**結果。

### 2.4 與 PositionDecisionEngine 的責任邊界

| 責任 | 負責元件 |
|---|---|
| 計算 trailing tier、stopFloor、effective stop 數值 | `PositionDecisionEngine` / `TrailingComputer`（既有）|
| 觸發 EXIT 訊號（盤中跌破 stop） | `PositionReviewService`（既有）|
| 24 小時 sticky EXIT | `PositionReviewService` + `PositionEntity.exit_signal_at`（既有）|
| 包裝 trailing + 健檢結果為 `holdQuality` 給人看 | **`PositionIntelligenceEngine`（本層新增）** |
| 給文字版「建議出場」/ 「續抱可加碼」 | **`PositionIntelligenceEngine`（本層新增）** |

---

## 3. 換股邏輯（PortfolioSwitchAnalyzer）

### 3.1 觸發時機

| 時機 | 用途 |
|---|---|
| 09:30 FinalDecision 之後 | 比對「今日 ENTER 候選 vs 既有持倉」，若候選顯著優於最弱持倉 → 提示換股 |
| 11:00 盤中 review | 同上但限制更嚴：盤中換股風險高，只在持倉 `holdQuality = REDUCE/EXIT` 且候選 grade = A+ 才提示 |
| 15:30 PostmarketAnalysis 之後 | 比對「明日候選 vs 既有持倉」，產出明日策略（§5）|
| 18:30 T86Confirm 之後 | 補強籌碼後再算一次；若結論翻轉，覆寫前一輪 `next_day_strategy` 並標 `revised_by_t86 = true` |

> 換股**不在 5 分鐘 monitor**內觸發；避免高頻噪音。

### 3.2 比較維度

對 「持倉股 P」 vs 「候選股 C」 做 5 維比較：

| 維度 | 計算 | 權重 |
|---|---|---:|
| `score` | C.finalScore - P.currentDailyScore | 0.30 |
| `strategyFit` | 兩者 strategyType 是否與當下市場匹配（A 級盤偏 Breakout / Momentum；B 級盤偏 Pullback） | 0.20 |
| `expectedMfe` | 由 `candidate_forward_tracking` 同類樣本取 `AVG(mfe_pct)`；若不足 20 樣本 fallback 到該策略全域均值 | 0.20 |
| `riskDelta` | C.riskScore - P.riskScore（C 更安全為正） | 0.15 |
| `themeMomentum` | C.themeRank vs P.themeRank（C 題材排名更前為正） | 0.15 |

組合 `switchScore`：

```
switchScore = 0.30 * normalize(scoreDelta)
            + 0.20 * strategyFitDelta       -- {-1, 0, +1}
            + 0.20 * normalize(mfeDelta)
            + 0.15 * normalize(-riskDelta)  -- 風險變低為正
            + 0.15 * normalize(-themeMomentumDelta)
```

`normalize` 函式：用 `clamp(delta / typical_range, -1, +1)` 線性映射；`typical_range` 寫進 score_config，可調。

### 3.3 KEEP / SWITCH / PARTIAL_SWITCH 判定

> 兩段門檻 + holdQuality gate；任一 gate 失敗即 KEEP。

#### 3.3.1 必須先過 holdQuality gate

| P.holdQuality | gate |
|---|---|
| `HIGH_HOLD` | 直接 KEEP；不允許換股 |
| `HOLD` | 允許進入分數比較 |
| `REDUCE` | 允許進入分數比較；門檻略放寬 |
| `EXIT` | 不進入比較；強制執行 EXIT，建議「先出再選」（不是 SWITCH，因為先後順序很重要） |

> EXIT 不是 SWITCH 的理由：SWITCH 暗示「同步換股」，但 EXIT 必須先平倉，新倉是另一個獨立決策；強行合併會誤導使用者「賣 A 同時買 B」。

#### 3.3.2 分數判定

| 條件 | 結論 |
|---|---|
| `switchScore ≥ +0.40` 且 P.holdQuality ∈ {HOLD, REDUCE} | `SWITCH`（建議全出 P，全進 C） |
| `+0.20 ≤ switchScore < +0.40` 且 P.holdQuality = `REDUCE` | `PARTIAL_SWITCH`（建議賣 P 一半，買 C 一半） |
| `+0.20 ≤ switchScore < +0.40` 且 P.holdQuality = `HOLD` | `KEEP`（差距未達 SWITCH 門檻，續抱）|
| `switchScore < +0.20` | `KEEP` |

**門檻可調**：

```
switch.full_threshold        = 0.40
switch.partial_threshold     = 0.20
switch.hold_quality_gate     = "HIGH_HOLD"   -- 列為 KEEP-only
switch.partial_eligible_hq   = "REDUCE"      -- 只在 REDUCE 才允許 PARTIAL_SWITCH
```

#### 3.3.3 額外 hard rules

- **資金不足 gate**：若 Austin 帳戶現金不夠買 C（依 `capital_summary` + `position.cost`），SWITCH 必須先賣 P；UI 必須明確標「需先平 P 後才能買 C」。
- **同題材 gate**：若 P 與 C 屬於同題材且 themeRank 都在 top 3，PARTIAL_SWITCH 不允許（避免風險集中）；改為 SWITCH 或 KEEP 二擇一。
- **單日換股次數上限**：每個交易日同一檔 P 最多被建議 SWITCH 1 次；重複觸發歸併到第一筆 review。
- **EXIT sticky 期內不允許再進場該標的**：剛 EXIT 的股票 24 小時內不可作為 SWITCH 的 C。

### 3.4 不自動下單

| 動作 | 系統行為 | Austin 必須做的 |
|---|---|---|
| `KEEP` | 寫 daily_review；不發特別通知 | 無 |
| `SWITCH` | 寫 daily_review + 一封獨立 LINE「🔄 換股建議：賣 P 買 C」 + paper_trade 模擬 | 手動賣 P + 手動買 C |
| `PARTIAL_SWITCH` | 同上但金額減半 | 手動依比例執行 |
| `EXIT` | 寫 daily_review + LINE「⚠️ 出場建議：P」 | 手動全出 |

> `SWITCH` 不是自動下單；`paper_trade` 模擬只用於 forward observation。

### 3.5 與 self-tuning 的隔離

- `PortfolioSwitchAnalyzer` 不寫任何 `score_config` / `strategy_tuning_*` 表。
- `self-tuning` 產生的 PENDING 建議**不**影響本層；本層只讀 `score_config` 當下值與 `final_decision` 結果。

---

## 4. 決策優先順序

> 當多個訊號同時出現，順序決定最終 `holdQuality` 與是否 SWITCH。違反順序就是 bug。

```
優先級（從上到下）：

1. EXIT signal（PositionDecisionEngine 已發 EXIT 或本層強制升級條件）
   → holdQuality = EXIT；換股建議 = 「先出再評估」（不能 SWITCH）

2. 強勢股 holdQuality = HIGH_HOLD
   → 自動 KEEP；不允許換股；TP 達標只賣 1/4 而非 1/3

3. 弱勢股 holdQuality = WEAK + risk MEDIUM/HIGH
   → holdQuality = EXIT；同步驟 1

4. 中性股（HOLD）但有更強候選（switchScore ≥ +0.40）
   → SWITCH；只在 holdQuality = HOLD/REDUCE 時生效

5. Breakout 候選 + 持倉空位 + 風險可控
   → 建議 ENTER（不是換股，是建倉）；由 FinalDecisionEngine 處理

6. Continuation 候選 + 既有持倉同題材龍頭強勢
   → 建議「同題材加碼」；只輸出 ADD_ON 訊號（不是 SWITCH），需 Austin 確認

7. Pullback 候選 + 大盤回測
   → 建議「視情況低吸」；輸出觀察級訊號，不主動建倉
```

### 4.1 衝突解決矩陣

| 條件 A | 條件 B | 結論 |
|---|---|---|
| holdQuality = EXIT | 候選 grade = A+ | 先 EXIT，候選獨立決策（不是 SWITCH） |
| holdQuality = HIGH_HOLD | 候選 grade = A+ 且 switchScore = +0.5 | KEEP（HIGH_HOLD 強鎖） |
| holdQuality = REDUCE | 候選 grade = A | 比較 switchScore：≥ +0.40 → SWITCH；+0.20 ≤ ... < +0.40 → PARTIAL_SWITCH |
| holdQuality = HOLD | 候選 grade = B | KEEP（B 不夠強） |
| holdQuality = HOLD + 同題材另一檔 grade = A | 候選與持倉題材一致 | KEEP（避免題材集中），改建議「ADD_ON 觀察」|
| 多檔持倉同時 EXIT | 候選只有 1 檔 A+ | 全部 EXIT，候選只進 1 檔 ENTER（其他空手） |

### 4.2 ADD_ON（加碼）特例

加碼**不是換股**；是「同檔 / 同題材續攻」的延伸建議，獨立輸出：

| 條件 | 加碼類型 |
|---|---|
| P.strategyType = `BREAKOUT` 且回測 5MA 不破 + 量縮 + 再上揚 | `BREAKOUT_PULLBACK_ADD` |
| P.strategyType = `PULLBACK` 且站回 5MA 後第 2-3 個交易日量增 | `PULLBACK_RECOVERY_ADD` |
| P.strategyType = `MOMENTUM_CONT` | **禁止加碼**（沿用 `strategy-design.md §1.4`） |

加碼建議寫入 `position_daily_review.suggested_addon_size`（建議倉位比例，如 0.3x），但**不**自動寫進 `position`。Austin 自行決定。

---

## 5. 明日策略輸出格式

> 由 `NextDayStrategyBuilder` 在 15:30 後 + 18:30 T86 確認後組合，寫入 `next_day_strategy` 表。LINE 模板於 18:50 / 隔日 08:30 用此資料渲染。

### 5.1 結構（JSON 格式）

```json
{
  "tradingDate": "2026-05-08",
  "generatedAt": "2026-05-07T18:35:00+08:00",
  "revisedByT86": false,

  "marketOutlook": {
    "expectedMarketGrade": "A",
    "expectedPhase": "MAIN_UPTREND",
    "keyLevels": { "twii": { "support": 19850, "resistance": 20200 } },
    "macroNotes": ["美股科技股延續強勢", "TSM ADR +1.8%"]
  },

  "positionActions": [
    {
      "symbol": "00631L",
      "stockName": "元大台灣50正2",
      "holdQuality": "HOLD",
      "strength": "STRONG",
      "risk": "MEDIUM",
      "currentPnlPct": 0.123,
      "trailingTier": "LOCK_5PCT",
      "suggestedStop": 24.05,
      "suggestedAction": "HOLD",
      "actionDetail": "5MA = 24.45，站穩後可考慮加碼 0.3x；爆量長黑或跌破 24.0 出場",
      "tpStatus": { "tp1Done": true, "tp2Done": false, "nextTp": 27.5 }
    },
    {
      "symbol": "2330",
      "stockName": "台積電",
      "holdQuality": "REDUCE",
      "strength": "NEUTRAL",
      "risk": "HIGH",
      "currentPnlPct": 0.21,
      "trailingTier": "LOCK_10PCT",
      "suggestedStop": 1080,
      "suggestedAction": "REDUCE",
      "actionDetail": "已過熱，建議賣 1/3 鎖利；剩餘部位 stop 上修到 1080"
    }
  ],

  "switchSuggestions": [
    {
      "fromSymbol": "1234",
      "toSymbol": "6770",
      "switchType": "PARTIAL_SWITCH",
      "switchScore": 0.28,
      "reason": "1234 holdQuality=REDUCE，6770 BREAKOUT A+ 候選 + 同期 mfe 預期 +9%",
      "fromExitPriceHint": 38.5,
      "toEntryPriceHint": "25.6 ~ 26.0",
      "toStopHint": 24.3,
      "toTp1Hint": 27.5
    }
  ],

  "newCandidates": [
    {
      "symbol": "3017",
      "stockName": "奇鋐",
      "strategyType": "PULLBACK",
      "grade": "A",
      "entryZone": "1320 ~ 1340",
      "stopHint": 1265,
      "tp1Hint": 1420,
      "tp2Hint": 1520,
      "rr": 2.9,
      "actionLabel": "ENTER_PRIMARY",
      "rationale": "回測 5MA 量縮，相對強度高"
    }
  ],

  "summary": {
    "headline": "明日策略：續抱 1 檔 + 減碼 1 檔 + 換股 1 檔 + 新進 1 檔",
    "totalPositions": 2,
    "exitCount": 0,
    "reduceCount": 1,
    "holdCount": 1,
    "switchCount": 1,
    "newEnterCount": 1,
    "addOnCount": 0,
    "restRecommended": false
  },

  "positionPlan": {
    "currentExposureRatio": 0.42,
    "targetExposureRatio": 0.55,
    "availableCash": 320000,
    "maxPerPositionPct": 0.20,
    "rationale": "大盤 A 級主升盤，可拉高曝險到 55%；單檔上限 20% 不變"
  }
}
```

### 5.2 LINE 模板（明日策略版）

```
[Trading System｜明日策略 2026-05-08]
🔵 預期 marketGrade=A   📡 主升段
🟢 行動：續抱 1 + 減碼 1 + 換股 1 + 新進 1

▎持倉動作：
  - 00631L 元大正2 [HOLD｜+12.3%] stop 24.05
    next：站穩 5MA 可加碼 0.3x；跌破 24.0 出場
  - 2330 台積電 [REDUCE｜+21%] stop 1080
    next：建議賣 1/3 鎖利

▎換股建議：
  - 1234 → 6770 [PARTIAL_SWITCH] score 0.28
    1234 弱勢中性，6770 BREAKOUT A+
    建議賣 1234 一半，買 6770 一半 ($25.6~26.0)

▎新候選：
  - 3017 奇鋐 [PULLBACK｜A] entry 1320~1340
    stop 1265 / tp1 1420 / RR 2.9

▎倉位建議：
  目前曝險 42% → 目標 55%
  可用現金 320k；單檔上限 20%

來源：Trading System
```

### 5.3 REST 時的明日策略

當 `expectedMarketGrade = C` 或 holdQuality 全部 REST：

```
[Trading System｜明日策略 2026-05-08]
🔴 預期 marketGrade=C   📡 RANGE_BOUND
🔴 行動：建議空手休息

▎持倉動作：
  - 6505 [EXIT｜-2.1%] 已連跌 3 日 + 跌破 5MA
    建議明日盤中出場

▎不出新單原因：
  - 大盤 breadth 連 2 日負；A 級主流族群退潮
  - 所有候選 grade ≤ B；無 A+/A

▎觀察池（不進場但追蹤）：
  - 2330 [WATCH] 等回測 1100 站穩
  - 2454 [WAIT_PULLBACK] 等站回 5MA

來源：Trading System
```

---

## 6. 工程契約

### 6.1 Service / Engine 責任邊界

| 元件 | 責任 | 不可做的事 |
|---|---|---|
| `PositionIntelligenceEngine` | 對單筆 position 計算 strength / risk / holdQuality；包裝 PositionDecisionEngine 的 trailing 結果為 suggestedStop；產 daily_review draft | **不寫 position 真倉**；不重算 trailing；不發 LINE |
| `PortfolioSwitchAnalyzer` | 比較持倉 vs 候選，產出 KEEP / SWITCH / PARTIAL_SWITCH / ADD_ON 建議 | 不下單；不寫 position；不寫 final_decision；不寫 paper_trade（由 PaperTradeService 自行 hook） |
| `NextDayStrategyBuilder` | 組合 PositionIntelligenceEngine + PortfolioSwitchAnalyzer + 候選清單 + capital_summary，輸出 next_day_strategy JSON | 不直接送 LINE（交 LineMessageBuilder 渲染） |
| `PositionDailyReviewService` | 寫入 / 查詢 `position_daily_review`；提供 `recordUserOverride` API | 不做評估邏輯（那是 engine 的事）|
| `PortfolioReviewController` | REST API（review / next-day-strategy / override） | 不做業務邏輯 |
| `PortfolioReviewJob` | 13:35 / 15:30 / 18:30 排程觸發 | 不會自動下單；不會自動寫 score_config |

### 6.2 API

```http
# 單筆持倉健檢
GET  /api/portfolio/review?symbol=2330
GET  /api/portfolio/review                    -- 全部持倉（回傳 list）
       Response: { positions: [{ symbol, holdQuality, strength, risk, suggestedStop, ... }], generatedAt }

# 明日策略
GET  /api/portfolio/next-day-strategy
       Response: §5.1 完整 JSON
GET  /api/portfolio/next-day-strategy?date=YYYY-MM-DD   -- 指定日期（歷史回顧）

# 換股建議
GET  /api/portfolio/switch-suggestions?status=PENDING
GET  /api/portfolio/switch-suggestions/{id}
POST /api/portfolio/switch-suggestions/{id}/dismiss
       Header: X-Austin-Confirm: true
       Body:   { reason: "我選擇續抱" }

# 手動 override（Austin 不認同系統評估）
POST /api/portfolio/review/{symbol}/override
       Header: X-Austin-Confirm: true
       Body:   { holdQuality: "HOLD", reason: "我相信這檔" }

# debug 用
POST /api/portfolio/review/run                -- admin only，手動重跑健檢
```

**安全**：
- `dismiss` / `override` / `run` 必須 `X-Austin-Confirm: true`。
- 所有 POST 端點記 `applied_by`（從 session / token 取）。
- `override` 只能改 `holdQuality`，不能改 `strength` / `risk`（保留證據鏈）。

### 6.3 DB Schema

> 新增 migration：`V31__portfolio_decision.sql`。沿用既有 `position` / `final_decision` 表，不更動其欄位。

```sql
-- 6.3.1 每日持倉健檢結果（每筆持倉 × 每日一列；盤中 in-memory 不寫）
CREATE TABLE IF NOT EXISTS position_daily_review (
    id                       BIGINT PRIMARY KEY AUTO_INCREMENT,
    review_date              DATE         NOT NULL,
    position_id              BIGINT       NOT NULL,
    symbol                   VARCHAR(16)  NOT NULL,
    stock_name               VARCHAR(64),

    -- 健檢核心
    strength                 VARCHAR(16)  NOT NULL,           -- STRONG / NEUTRAL / WEAK
    strength_score           INT          NOT NULL,           -- §1.2 sum
    strength_evidence_json   JSON         NOT NULL,           -- 各 +N / -N 欄位的 raw 值
    risk                     VARCHAR(16)  NOT NULL,           -- LOW / MEDIUM / HIGH
    risk_score               INT          NOT NULL,
    risk_evidence_json       JSON         NOT NULL,
    hold_quality             VARCHAR(16)  NOT NULL,           -- HIGH_HOLD / HOLD / REDUCE / EXIT
    hold_quality_reason      VARCHAR(500) NOT NULL,
    sticky_reason            VARCHAR(64),                     -- 例：EXIT_STICKY_24H / RECOVERY_3_ROUNDS_REQUIRED

    -- 停利停損
    trailing_tier            VARCHAR(24)  NOT NULL,           -- UNLOCKED / BREAKEVEN / LOCK_5PCT / LOCK_10PCT / LOCK_20PCT
    pos_engine_stop          DECIMAL(18,4),                   -- PositionDecisionEngine trailing
    suggested_stop           DECIMAL(18,4),                   -- §2.1 max(...) 結果
    suggested_stop_basis     VARCHAR(64),                     -- 5MA / 10MA / SWING_LOW / ATR / EXIT_TEXT
    suggested_action         VARCHAR(24)  NOT NULL,           -- HOLD / REDUCE / EXIT / ADD_ON_OK
    action_detail            VARCHAR(500),

    tp_status_json           JSON,                            -- { tp1Done, tp2Done, tp3Done, nextTp }
    suggested_addon_size     DECIMAL(5,4),                    -- 0.0 ~ 1.0；NULL 表示不建議加碼

    -- 切換建議（若這檔被建議換出）
    switch_suggestion_id     BIGINT,                          -- FK portfolio_switch_suggestion.id

    -- 手動 override
    user_override_quality    VARCHAR(16),                     -- 若 Austin override，記新值
    user_override_reason     VARCHAR(500),
    user_override_by         VARCHAR(64),
    user_override_at         DATETIME,

    -- 資料品質
    stale_quote              BOOLEAN      NOT NULL DEFAULT FALSE,
    partial_data             BOOLEAN      NOT NULL DEFAULT FALSE,
    partial_data_reason      VARCHAR(255),

    generated_at             DATETIME     NOT NULL,
    UNIQUE KEY uk_review_date_position (review_date, position_id),
    INDEX idx_review_symbol_date (symbol, review_date),
    INDEX idx_hold_quality (hold_quality, review_date)
);

-- 6.3.2 換股建議（每次比對一個 from-to pair 一列）
CREATE TABLE IF NOT EXISTS portfolio_switch_suggestion (
    id                       BIGINT PRIMARY KEY AUTO_INCREMENT,
    suggested_at             DATETIME NOT NULL,
    suggested_for_date       DATE NOT NULL,                   -- 通常是隔日交易日
    source_phase             VARCHAR(24) NOT NULL,            -- POSTMARKET_1530 / T86_1830 / OPENING_0930 / MIDDAY_1100
    from_position_id         BIGINT NOT NULL,
    from_symbol              VARCHAR(16) NOT NULL,
    to_symbol                VARCHAR(16) NOT NULL,
    to_strategy_type         VARCHAR(24),
    to_grade                 VARCHAR(16),
    switch_type              VARCHAR(24) NOT NULL,            -- KEEP / SWITCH / PARTIAL_SWITCH / ADD_ON / EXIT_THEN_DECIDE
    switch_score             DECIMAL(8,4) NOT NULL,
    score_components_json    JSON NOT NULL,                   -- {scoreDelta, strategyFit, mfeDelta, riskDelta, themeMomentum}
    reason                   VARCHAR(1000) NOT NULL,
    from_exit_price_hint     DECIMAL(18,4),
    to_entry_price_hint      VARCHAR(64),                     -- 區間文字 "25.6 ~ 26.0"
    to_stop_hint             DECIMAL(18,4),
    to_tp1_hint              DECIMAL(18,4),
    status                   VARCHAR(24) NOT NULL,            -- PENDING / DISMISSED / SUPERSEDED / EXPIRED / ACTED_PAPER / ACTED_LIVE
    user_decision_at         DATETIME,
    user_decision_by         VARCHAR(64),
    user_decision_reason     VARCHAR(500),
    INDEX idx_switch_status (status, suggested_at),
    INDEX idx_switch_from_date (from_symbol, suggested_for_date)
);

-- 6.3.3 明日策略快照（每日一筆完整 plan）
CREATE TABLE IF NOT EXISTS next_day_strategy (
    id                       BIGINT PRIMARY KEY AUTO_INCREMENT,
    trading_date             DATE NOT NULL,
    generated_at             DATETIME NOT NULL,
    revised_by_t86           BOOLEAN NOT NULL DEFAULT FALSE,
    market_outlook_json      JSON NOT NULL,
    position_actions_json    JSON NOT NULL,
    switch_suggestions_json  JSON,
    new_candidates_json      JSON,
    summary_json             JSON NOT NULL,
    position_plan_json       JSON NOT NULL,
    line_message_text        TEXT,                            -- 渲染好的 LINE 文字（避免每次重組）
    superseded_by_id         BIGINT,                          -- 18:30 T86 修正後指向新版本
    UNIQUE KEY uk_strategy_date_version (trading_date, generated_at),
    INDEX idx_strategy_date (trading_date)
);
```

> 既有 `position` / `position_review_log` / `final_decision` / `paper_trade` 表**不改動**。`position_review_log` 仍由 `PositionReviewService` 寫入，本層只讀。

### 6.4 與既有 entity 的對應

| 既有 | 本層使用方式 |
|---|---|
| `PositionEntity` | 唯讀；取 entryPrice / shares / strategyType / exit_signal_at |
| `PositionReviewLogEntity` | 唯讀；取最新 reviewStatus / trailingTier / effectiveStop |
| `FinalDecisionEntity` | 唯讀；取今日 / 明日 selected_stocks 作為候選 universe |
| `CandidateForwardTrackingEntity` | 唯讀；取 mfeAvg / winRate 作為 expected mfe |
| `MarketIndexDailyEntity` | 唯讀；取 0050 同期報酬作 relativeStrength 與 benchmark |
| `ScoreConfig` | 唯讀；取 §3.3 / §4 / §6.5 各 key |

### 6.5 score_config keys（新增）

```
# 健檢
portfolio.review.strength.strong_threshold      = 4
portfolio.review.strength.weak_threshold        = -3
portfolio.review.risk.high_threshold            = 6
portfolio.review.risk.medium_threshold          = 3
portfolio.review.recovery_consecutive_rounds    = 3
portfolio.review.intraday_eval_minutes          = 60       # 整點觸發

# 動態 stop
portfolio.stop.high_hold_basis                  = "5MA*0.985"
portfolio.stop.hold_basis                       = "max(5MA*0.985, swingLow5*0.99)"
portfolio.stop.reduce_basis                     = "max(currentPrice*0.985, swingLow3*0.995)"

# 換股
portfolio.switch.full_threshold                 = 0.40
portfolio.switch.partial_threshold              = 0.20
portfolio.switch.weight.score                   = 0.30
portfolio.switch.weight.strategy_fit            = 0.20
portfolio.switch.weight.expected_mfe            = 0.20
portfolio.switch.weight.risk_delta              = 0.15
portfolio.switch.weight.theme_momentum          = 0.15
portfolio.switch.normalize_typical_range_score  = 1.5
portfolio.switch.normalize_typical_range_mfe    = 0.05
portfolio.switch.normalize_typical_range_risk   = 4
portfolio.switch.same_theme_partial_disabled    = true
portfolio.switch.daily_max_per_position         = 1
portfolio.switch.exit_sticky_block_hours        = 24

# 明日策略
portfolio.next_day.target_exposure.market_a     = 0.55
portfolio.next_day.target_exposure.market_b     = 0.30
portfolio.next_day.target_exposure.market_c     = 0.0
portfolio.next_day.max_per_position_pct         = 0.20
portfolio.next_day.line_template_version        = "v1"
```

### 6.6 排程

| 時間 | Job | 觸發 |
|---|---|---|
| 09:35 | `PortfolioOpeningReviewJob` | 09:30 FinalDecision 後重評 holdQuality（不寫 daily_review，只 in-memory + 必要時 LINE 警示） |
| 11:00 | `PortfolioMiddayReviewJob` | 同上但允許輸出 SWITCH（限制嚴）|
| 13:35 | `PortfolioPostCloseReviewJob` | 寫 `position_daily_review` 完整版 |
| 15:35 | `NextDayStrategyDraftJob` | 寫 `next_day_strategy`（draft）|
| 18:35 | `NextDayStrategyT86RefineJob` | T86 確認後重算；若結論變更 → 寫新版本 + `revised_by_t86 = true`，舊版本 `superseded_by_id` 指向新 id；發 LINE |

> 所有 job 失敗：**只**寫 SYSTEM_ALERT，不自動 fallback、不自動 EXIT、不自動 SWITCH。

### 6.7 測試（測試碼不在本文件，僅列建議測試類別）

#### Unit Tests

| 測試類別 | 範圍 |
|---|---|
| `PositionIntelligenceEngineTests` | strength / risk / holdQuality 各分級邊界；EXIT 強制升級條件；HIGH_HOLD 升級需 3 輪 |
| `PositionIntelligenceStaleQuoteTests` | quote stale → 維持上一輪 + partial_data flag；連 3 輪 stale → SYSTEM_ALERT |
| `PortfolioSwitchAnalyzerTests` | switchScore 計算；HIGH_HOLD gate；EXIT 不允許 SWITCH；同題材不允許 PARTIAL_SWITCH |
| `PortfolioSwitchAnalyzerThresholdTests` | full_threshold / partial_threshold 邊界（0.40 命中 SWITCH，0.39 → KEEP） |
| `PortfolioSwitchExitStickyTests` | EXIT 24 小時內該標的不可作為 to_symbol |
| `NextDayStrategyBuilderTests` | REST 時的 plan / 含 switch / 含 addon / 含 newCandidate 各情境 |
| `PositionDailyReviewServiceTests` | override 寫入；user_override_at / user_override_by 必填 |
| `PortfolioReviewControllerTests` | dismiss / override 缺 X-Austin-Confirm → 403；override 改 strength → 400 |
| `NextDayStrategyT86SupersedeTests` | 18:30 重算結論變更 → 舊版本 superseded_by_id 寫入；新版本 revised_by_t86 = true |

#### Integration Tests

| 測試類別 | 場景 |
|---|---|
| `PortfolioReviewFlowIntegrationTests` | 13:35 跑完整流程：每筆 position 寫一筆 daily_review；資料正確 |
| `NextDayStrategyFlowIntegrationTests` | 15:35 draft → 18:35 T86 refine → LINE 模板渲染；版本鏈正確 |
| `PortfolioSwitchPaperTradeIntegrationTests` | SWITCH 建議 → paper_trade 模擬被觸發；真倉 position 完全不變 |
| `PortfolioReviewSafetyTests` | 反向驗證：執行整輪流程 → `position` 行數不變、`final_decision` 不變、`score_config` 不變、broker API 未呼叫；DI 圖檢查 PositionIntelligenceEngine 沒 PositionRepository writer |
| `PortfolioReviewExitStickyHonorTests` | PositionDecisionEngine 已發 EXIT → 本層 holdQuality 強制 EXIT，不被任何 SWITCH 蓋過 |

#### 不影響既有測試

- `mvn -q test` 全綠（含 `PositionDecisionEngineTests` / `PositionDecisionEngineMomentumTests` / `PositionReviewExitAlertTests` / `PositionReviewExitAutoCloseTests` / `FinalDecisionCandidateRequestTests` / `StrategyTuningEngineTests` / `TuningEvaluationEngineTests`）。

### 6.8 安全限制（逐條對應 §0 hard rules）

| # | 限制 | 設計如何保證 | 驗收 |
|---|---|---|---|
| 1 | 不自動下單 | 本層 service 全部不持有 broker / `PositionService.write` 依賴；DI 圖只 inject Repository read-only + 既有 read service | 整合測試：跑一輪 → broker mock 未被呼叫；position 行數不變 |
| 2 | 不自動加碼 | `suggested_addon_size` 只寫 `position_daily_review`；不會觸發 `position.shares` 寫入 | 單元測試：mock 全流程，verify `PositionRepository.save(position)` 從未被呼叫 |
| 3 | 不自動換股 | `PortfolioSwitchAnalyzer` 只寫 `portfolio_switch_suggestion`（status=PENDING）；不直接觸發 paper_trade（paper_trade 由 PaperTradeService listener 自行訂閱事件） | 整合測試：SWITCH 寫入後 position 不變；paper_trade 由獨立 listener 處理 |
| 4 | 不依單一 tick 改 holdQuality | 5 分鐘 monitor 只更新部分 risk 旗標；holdQuality 升降需通過 §1.5 sticky/連續確認 | 單元測試：模擬單根 1 分 K 跌 -3% → holdQuality 不立即降級 |
| 5 | 不呈現為已執行 | LINE / UI 文案模板硬編碼「建議」字眼；snapshot 測試禁止「已換股 / 已加碼」 | 模板測試：拒絕含「已換股」字串 |
| 6 | 不覆寫 position 真倉狀態 | DI 圖：本層 service 不注入 `PositionRepository` 的 save 介面；只用 read-only repository | 編譯期檢查 + `PortfolioReviewSafetyTests` |
| 7 | EXIT 絕對優先 | §4 衝突解決矩陣：EXIT 永遠列第一；任何 SWITCH 邏輯先檢查 holdQuality != EXIT | 單元測試：EXIT + 候選 A+ → 結論 EXIT_THEN_DECIDE，不是 SWITCH |
| 8 | trailing 只可上修 | 本層 `suggestedStop = max(lastSuggestedStop, ...)`；除 EXIT 文字旗標外無例外 | 單元測試：5MA 下移情境 → suggestedStop 不下調 |
| 9 | 與真倉 / paper 隔離 | `is_shadow=true` 的標的在 query 時透過 join `position` 過濾掉 | 整合測試：建立 paper shadow → 不出現在 daily_review |
| 10 | 沒有持倉時也輸出 | `NextDayStrategyBuilder.build()` 對空 positionList 仍要輸出 §5.3 REST 版本 | 單元測試：空 positions → headline 含「建議空手休息」 |
| 11 | override 留軌跡 | `position_daily_review.user_override_*` 三欄位 NOT NULL（一旦寫入時）；API 必填 reason | 整合測試：override → DB 三欄位皆有值 |

### 6.9 Phase 實作順序（建議給 Codex）

| Phase | 範圍 | 預估工作量 |
|---|---|---|
| **P0** | DB migration `V31__portfolio_decision.sql`、Entity / Repository（read-only for engine 部分） | 1 工作日 |
| **P1** | `PositionIntelligenceEngine` 骨架 + strength / risk 評估器 + holdQuality 推導 + sticky 規則 | 2 工作日 |
| **P2** | `PositionDailyReviewService.persistDailyReviews` + 13:35 排程 | 1 工作日 |
| **P3** | `PortfolioSwitchAnalyzer` + switchScore + KEEP/SWITCH/PARTIAL_SWITCH 判定 | 2 工作日 |
| **P4** | `NextDayStrategyBuilder` + 15:35 / 18:35 排程 + supersede 鏈 | 2 工作日 |
| **P5** | REST API（review / next-day-strategy / dismiss / override）+ X-Austin-Confirm | 1 工作日 |
| **P6** | LINE 模板（明日策略 / SWITCH 建議 / EXIT 警示）+ Dashboard `Portfolio` 分頁 | 2 工作日 |
| **P7** | E2E：模擬 5 個交易日資料 → 驗證 daily_review 寫入、SWITCH 建議命中、override 路徑、EXIT sticky 不被覆寫 | 1 工作日 |

> 建議在 P0 ~ P3 完成後跑 10 個交易日「dry mode」（寫表但 LINE / UI 隱藏 SWITCH 建議），確認 holdQuality 分佈與 SWITCH 命中率合理後再開 LINE 通知與 dismiss/override 入口。

---

## 7. 開放議題（待 Austin 確認）

1. **HIGH_HOLD 是否完全鎖死換股？** 目前設計：HIGH_HOLD = KEEP-only，不允許任何 SWITCH。但若市場出現「真正的換股最佳時機」（HIGH_HOLD 但同題材另一檔更強）是否要破例？預設不開放，避免追逐強勢股反受套牢。
2. **PARTIAL_SWITCH 比例是否要可調？** 目前固定一半一半。是否依 holdQuality 程度動態調整（REDUCE 嚴重 → 7/3，輕微 → 5/5）？v1 預設 5/5。
3. **同題材集中度上限**：本文件用 hard rule 拒絕「同題材 PARTIAL_SWITCH」。是否要改為「整個 portfolio 同題材 ≤ 50%」的軟限制？v1 不引入，待後續觀察數據。
4. **盤中換股是否值得？** 11:00 有 SWITCH 建議。是否高頻換股反而扣手續費？建議 v1 啟用但只在 holdQuality = REDUCE/EXIT 才允許；v2 觀察資料後決定是否關閉盤中 SWITCH。
5. **next_day_strategy 是否要保留歷史版本？** 18:35 重算覆寫前版本（用 superseded_by_id 鏈）。是否保留所有版本供 review？v1 預設保留全部（成本不高）。
6. **override 過期**：Austin 對某檔 override 為 HOLD，過 5 個交易日後是否自動失效？v1 預設「override 只對當日 review 有效」；隔日重評不繼承。

---

## 附錄 A：本設計與既有 code 的對應

| 既有檔案 | 本設計變更 |
|---|---|
| `PositionDecisionEngine.java` | **不變動**；本層讀其輸出 |
| `PositionReviewService.java` | **不變動**；本層讀 `position_review_log` |
| `FinalDecisionEngine.java` | **不變動**；本層讀 `final_decision` 作為候選 universe |
| `PaperTradeService.java` | **不變動**；SWITCH 建議若被執行為 paper，由 PaperTradeService 自行 listener 接收事件 |
| `ScoreConfigService` | 新增 §6.5 keys（透過既有 upsert 路徑） |
| `LineMessageBuilder` | 新增「明日策略」/「SWITCH 建議」/「EXIT 警示」三個模板 |
| 新增 | `PositionIntelligenceEngine` / `PortfolioSwitchAnalyzer` / `NextDayStrategyBuilder` / `PositionDailyReviewService` / `PortfolioReviewController` / `PortfolioReviewJob` 系列 / `position_daily_review` / `portfolio_switch_suggestion` / `next_day_strategy` 三表 |

## 附錄 B：與既有 trading-upgrade 文件的關係

- 與 `strategy-design.md`：本層**消費** §1（三策略分類）+ §4（trailing 五段）+ §5（Dashboard 四層語意）；不取代。
- 與 `self-tuning-design.md`：本層**唯讀** `score_config`；自我調參的 PENDING 建議**不**影響本層即時決策。
- 與 `after-tuning-validation-design.md`：完全獨立；驗證模組不會檢視 portfolio_decision 的 SWITCH 命中率（那是 v2 議題）。
- 與 `code-implementation-plan.md` / `files-to-change.md` / `test-plan.md`：本文件僅描述 portfolio decision 層；具體檔案異動清單與 test 編寫由 Codex 在實作 phase 補上。

---

**結束。等 Austin / Codex review。**
