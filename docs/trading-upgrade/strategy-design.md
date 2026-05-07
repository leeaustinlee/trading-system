# 台股 AI 交易系統｜實戰化升級策略設計

> 版本：strategy-design v1（2026-05-07）
> 用途：為 Java FinalDecisionService / PositionReviewService / PaperTradeService 等核心引擎提供「策略模型 + 追蹤 + 觀察 + 持倉風控 + 儀表板語意」的完整升級設計，供 Codex 實作參考。
> **核心原則：本系統是「決策輔助 + 紀錄 + 驗證」工具，不自動下單**。所有 ENTER 訊號需 Austin 手動執行；shadow / observation 紀錄與真實下單在資料層必須隔離。

---

## 0. 升級背景與痛點

### 0.1 現況觀察（讀 `FinalDecisionEngine` / `PriceGateEvaluator` / `VetoEngine` / `PositionDecisionEngine`）

| 痛點 | 觀察到的 root cause | 後果 |
|---|---|---|
| 永遠 WAIT | `priceGateEvaluator` 在 `belowOpen=true && hasVwap=false` 時 fallback WAIT；ChasedHigh 在缺資時 NO_DATA 直接 PASS 但其他 gate 又把候選打回 WAIT；多個 gate 串接後候選通過率極低 | LINE 一直發「等站回 VWAP」，Austin 看不到可用標的 |
| 漲停 / 近漲停股直接消失 | `ChasedHighEntryEngine` 預設離日高 < 2% 即 BLOCK；`tradabilityTag="漲幅過大"` 走 soft penalty -1.0 但常導致跌出 grade A 門檻 | 飆股當日漲停打開洗盤的「續強買點」永遠抓不到 |
| 防守過度，回測樣本太少 | shadow mode 僅 1 筆/30 天樣本（P0.6 commit log）；reject / wait 沒有 forward 追蹤 | 無法評估 gate 太嚴 vs gate 正確；策略無法持續優化 |
| 持倉只有 EXIT/HOLD/STRONG/WEAKEN/TRAIL_UP 五態，無分段鎖利 | `PositionDecisionEngine.computeTrailingAction` 三段（3%/5%/8%）後就用 dayLow 移動，缺少「>=20% / >=30% 鎖更高利潤」分段 | 飆股回吐 50% 才出場 |
| Dashboard / LINE 把 monitor WATCH 顯示成 trade ENTER | 現行 `marketState` `monitorState` `tradeDecision` `finalDecision` 散落在不同 service，UI/通知層混用 | 使用者誤以為系統建議買，實際是觀察 |

### 0.2 升級目標

1. **策略可分流**：Breakout / Pullback / Momentum Continuation 三條獨立路徑，各自評分、Gate、停損停利。
2. **永遠 WAIT 必有出口**：每條策略都要有「為什麼今天空手」的具名理由，且若連續 N 個交易日 grade=C，要降低門檻試單而不是無限等。
3. **漲停 / 近漲停股不消失**：ChasedHigh 改分流到 Momentum Continuation；不直接砍掉，落到 watch_only 桶。
4. **錯過飆股可量化**：rejected / wait / watch_only 全部寫進 `trade_observation` 表，T+1/T+3/T+5/T+10 自動記錄結果。
5. **持倉分段鎖利**：>=5% / >=10% / >=20% / >=30% 四段，配合 5MA / 10MA / ATR / swing low。
6. **Dashboard 語意分層**：marketState（市場）/ monitorState（盤中監控）/ tradeDecision（今日是否進場）/ finalDecision（哪幾檔）四層，UI 與 LINE 不得混用。

---

## 1. 三策略模型

> 三策略**互斥**：每檔候選股每天只能被分到一條策略路徑。分類由 `StrategyClassifier`（待新增）依 selectedStrategy 欄位決定，可由 Codex 在送 candidate 時帶 `strategy_hint`，Java 引擎做最終 binding。

### 1.1 通用判定表（先分流再評分）

| 條件 | → 進入策略 |
|---|---|
| 站上近 20 日新高 + 量增 1.8x 以上 + 距前波壓力 < 1% | Breakout |
| 已突破過 5-10 日 + 回測 5MA / 10MA / 前波頸線 + 量縮 + 不破關鍵價 | Pullback |
| 已突破超過 10 日且持續創高 + 5MA 順向 + 連續 2-3 根低量整理後再放量續攻 | Momentum Continuation |
| 同時符合多條 | 優先順序：Pullback > Breakout > Momentum Continuation（風險低 → 高） |
| 一條都不符合 | 不進場（不再強塞 SETUP 桶）|

---

### 1.2 Breakout Strategy（突破 / 飆股 / 主升段）

#### 適用市場情境
- `marketGrade ∈ {A, A+}`（B 級可允許但倉位 0.5x；C 級禁止）
- 主流族群一致向上（同題材 ≥ 3 檔同步上漲，或題材 `themeRank ≤ 2`）
- 成交量總值 > 60 日均量
- 非除權息 / 法說會前 5 個交易日

#### 選股條件（須滿足 5 / 7）
1. 突破近 20 日新高（盤中或前一日收盤）
2. 突破量 ≥ 5 日均量 1.8x
3. 距離前波頸線壓力突破幅度 0.5% ~ 5%（過遠進入 Momentum）
4. 5MA 站上 20MA 且 5MA 上升中
5. RSI(14) ∈ [55, 75]（過熱進 Momentum）
6. 法人連 2 日買超或當日大買 ≥ 1000 張
7. 題材 `final_theme_score ≥ 7.5` 且 `themeRank ≤ 3`

#### 評分因子（0-10，加權後 final score）
| 因子 | 權重 | 計分 |
|---|---|---|
| 突破型態完整度（base 寬度、頸線清晰度） | 25% | 0-10 |
| 量能放大倍率 | 20% | linear: 1x→0, 2x→6, 3x→8, 4x+→10 |
| RR ratio (TP1 / Stop) | 20% | clamp(RR × 2.5, 0, 10) |
| 主流族群一致性 | 15% | 同題材同步漲家數 / 該題材成分股 |
| 法人買超強度 | 10% | 連 N 日買 + 當日張數 |
| 大盤 grade boost | 10% | A=+10, B=+5, C=0 |

#### Gate 條件（Breakout 專屬，hard block 一條即拒）
- `falseBreakout = true`（突破收回 / 上影線 ≥ 70%）→ HARD REJECT
- `volumeRatio < 1.3`（突破無量）→ HARD REJECT
- `currentPrice < breakoutLevel`（破回突破點）→ HARD REJECT
- `marketGrade = C` → HARD REJECT
- `entryTooExtended = true`（距突破點 > 5%）→ **不 reject，分流到 Momentum Continuation**
- `belowPrevClose && bearOrPanic` → HARD REJECT（沿用現有 PriceGateEvaluator）

#### RR 要求
- `min_rr = 2.0`（Breakout 已是高勝率型態，可放寬於 Momentum）
- `target_rr ≥ 2.5` 才進 A+ bucket

#### Entry / Stop / TP 邏輯
- **Entry zone**：突破點 ~ 突破點 + 2%（不追超過 2%）
- **Stop**：突破點下方 1.5% 或前波頸線下緣，取較高者；不得 < 進場價 -6%
- **TP1**：突破點 + 1× ATR(20) 或 +6%，取較高
- **TP2**：突破點 + 2.5× ATR(20) 或 +13%，取較高
- **加碼點**：拉回 5MA 不破再上揚 → 可加 0.5x 倉位（記入 `add_on_log`）

#### 決策映射（WAIT / WATCH / ENTER / REJECT）
| 條件 | 決策 |
|---|---|
| 全 Gate 通過 + score ≥ A+ 門檻 + RR ≥ 2.5 | **ENTER**（max 2 檔）|
| 全 Gate 通過 + score 在 A 門檻 | **ENTER**（倉位 0.7x）|
| 全 Gate 通過 + score 在 B 門檻 + market=A | **ENTER**（倉位 0.5x，試單）|
| Gate 通過但盤中 priceGate=WAIT（如剛跌破 VWAP 等待回站） | **WAIT**（每 5 分鐘重評一次，最多等到 11:00）|
| Gate 通過但 score 未達 B 門檻 | **WATCH**（不進場，但寫 `trade_observation` 追蹤錯過）|
| HARD REJECT | **REJECT**（寫 observation，但標 `expected_to_fail = true`）|

#### 怎麼避免「永遠 WAIT」
1. **WAIT 有時限**：09:30 進入 WAIT 的候選若到 10:30 仍未轉 ENTER → 自動降級為 WATCH（不再每 5 分鐘騷擾），並寫入 `trade_observation` 等 T+N 結果。
2. **連續空手保護**：若連續 ≥ 5 個交易日 finalDecision = REST，下一日門檻自動降 0.3 分（A+ 8.8 → 8.5；可 config）。連續 ≥ 10 日再降 0.3。回到 ENTER 後重置。
3. **B grade 試單救援**：當日無 A+/A 但 B 桶有候選且 market ≠ C → 強制至少送 1 檔 B_TRIAL 進 ENTER（已是現行行為，需確認 `decision.max_pick_b ≥ 1`）。

#### 怎麼避免「漲停 / 近漲停直接消失」
1. **離日高 < 2% 不再 hard block**：改為「分流到 Momentum Continuation」。
2. **漲停 (`price ≥ prevClose × 1.095`)**：移到 sectorIndicator 桶（族群燈號），不進 Breakout ENTER 但保留為觀察池，明日續強再進場。
3. **`tradabilityTag="漲幅過大"`**：走 soft penalty -0.5（從 -1.0 降）但不影響 Momentum 桶分配。

---

### 1.3 Pullback Strategy（強勢股回測 / 低吸）

#### 適用市場情境
- `marketGrade ∈ {A, B}`（C 級也可考慮，但只允許強勢族群龍頭）
- 大盤 5 日線方向不變
- 個股已在前 5-15 個交易日完成突破，現在進入「健康回測」

#### 選股條件（須滿足 5 / 6）
1. 近 15 個交易日內曾突破近 60 日新高
2. 當前價位回測至 5MA / 10MA / 前波頸線 ± 1.5%
3. 回測過程中 5 日量縮（< 突破日量的 0.7x）
4. 大盤回測幅度 < 個股突破後最大漲幅的 50%（個股相對強勢）
5. 法人未轉賣超（連 3 日合計賣超 < 突破日買超的 30%）
6. 題材仍在 top 3，`final_theme_score ≥ 7.0`

#### 評分因子
| 因子 | 權重 | 計分 |
|---|---|---|
| 回測位置乾淨度（離 MA 多近 / 是否破關鍵價） | 30% | 0-10 |
| 量能萎縮品質（健康縮量） | 20% | 量縮 30-50% 為佳 |
| 相對強度（個股 / 大盤同期跌幅） | 15% | RS ≥ 1.5 → 10 分 |
| 籌碼穩定度（法人未倒貨） | 15% | 0-10 |
| RR ratio | 10% | 同 Breakout |
| 題材延續 | 10% | `themeContinuationScore` 直接用 |

#### Gate 條件
- 跌破 20MA → HARD REJECT
- 跌破突破日的「前波頸線」→ HARD REJECT
- 量能異常放大 + 收長黑（`volumeRatio > 1.5 && bodyRatio < -0.03`）→ HARD REJECT
- `marketGrade = C` 且非族群龍頭 → HARD REJECT
- `holding_days_since_breakout > 20` → 移出 Pullback 池（已不算「強勢回測」）

#### RR 要求
- `min_rr = 2.5`（Pullback 是 best entry，理應風險小報酬高）
- `target_rr ≥ 3.0` 進 A+

#### Entry / Stop / TP 邏輯
- **Entry zone**：5MA / 10MA ± 1%；可分 2 段進場（第一段 50% 倉位、第二段確認站回再補 50%）
- **Stop**：MA 下方 2% 或前低，取較高；不得 < 進場價 -5%
- **TP1**：回到突破日當日高點（前波高）→ +6~8%
- **TP2**：突破前波高後 +1.5× ATR 或 +12~15%
- **加碼點**：站回 5MA 後第 2-3 個交易日量增 → 加 0.3x 倉位

#### 決策映射
| 條件 | 決策 |
|---|---|
| 全 Gate 通過 + 回測位置乾淨 + 量縮 + score ≥ A+ | **ENTER**（max 2 檔）|
| Gate 通過 + score 在 A | **ENTER**（倉位 0.8x，Pullback 倉位較高，因勝率高）|
| Gate 通過 + score 在 B | **WATCH**（Pullback 不做試單；錯了就錯了）|
| 已碰到 entry zone 但量未縮 / 還在下殺 | **WAIT**（waitPullback 桶，每 5 分鐘重評）|
| 跌破 stop 線 | **REJECT** + 標 `pullback_failed`，寫 observation |

#### 怎麼避免「永遠 WAIT」
- Pullback 的 WAIT 限時 **2 個交易日**：第一日進 wait_pullback 桶，第二日仍未站回 → 直接 REJECT（`reason=pullback_too_long_no_recovery`），釋放追蹤資源。

#### 怎麼避免「漲停消失」
- Pullback 本質就是回測中的股票，不會碰到漲停問題。但若回測過程中發生「先漲停打開後拉回」，照樣判定為 Pullback 候選，**不**因日內漲停痕跡 reject。

---

### 1.4 Momentum Continuation Strategy（續強 / 第二段）

#### 適用市場情境
- 大盤 `marketGrade = A`（B 級嚴格限制：只允許 1 檔 + 倉位 0.4x；C 級禁止）
- 個股已突破超過 10 個交易日，仍持續創高
- 是現行 `MOMENTUM_CHASE` 分支的演進版

#### 選股條件（須滿足 5 / 6）
1. 距首次突破 ≥ 10 個交易日
2. 期間累積漲幅 ≥ 15% 但 ≤ 50%（過熱另立 sectorIndicator 桶）
3. 5MA 一直未跌破
4. 連續 2-3 根低量整理後當日量再放大（≥ 5MA 量 1.5x）
5. 仍是主流族群龍頭（`themeRank = 1` 或 `final_theme_score ≥ 8.5`）
6. RSI(14) ∈ [60, 80]

#### 評分因子
| 因子 | 權重 | 計分 |
|---|---|---|
| 整理形態品質（low volatility consolidation） | 25% | ATR 收斂 + 不破 5MA |
| 量能再放大 | 20% | 同 Breakout |
| 主流族群龍頭認證 | 20% | `themeRank=1` → 10 分 |
| 大盤強度 | 15% | A=10, B=4, C=0 |
| 進場時機（不超過 5MA + 3%） | 10% | 越近 5MA 越高 |
| 籌碼（外資 / 投信 / 大戶持續） | 10% | 0-10 |

#### Gate 條件
- 跌破 5MA → HARD REJECT（Momentum 對 5MA 極敏感）
- `volumeSpikeLongBlack = true`（爆量長黑）→ HARD REJECT
- 距整理區上緣 > 3% → HARD REJECT（變成追高）
- `marketGrade ∈ {C}` → HARD REJECT
- `entryTooExtended = true` 且距日高 < 2% → 進入 sectorIndicator（不進場）

#### RR 要求
- `min_rr = 1.5`（Momentum 本質低 RR 高勝率）
- 不要求 RR ≥ 2.5；改要求 `momentum_score ≥ 7`

#### Entry / Stop / TP 邏輯
- **Entry zone**：整理區上緣突破當下，或拉回 5MA + 1% ~ + 3%
- **Stop**：5MA 下方 1.5%，或進場價 -2.5%，取較高
- **TP1**：整理區寬度的 1× projection 或 +6%
- **TP2**：整理區寬度的 2× projection 或 +12%
- **time stop**：3 個交易日未到 TP1 → 出場（沿用現行 `momentum.max_holding_days = 3`）
- **加碼**：禁止加碼（Momentum 已在 second leg，加碼風險太高）

#### 決策映射
| 條件 | 決策 |
|---|---|
| 全 Gate 通過 + score ≥ A+ + market=A | **ENTER**（max 1 檔，Momentum 高風險，限 1）|
| Gate 通過 + score 在 A + market=A | **ENTER**（倉位 0.5x，試單）|
| Gate 通過但 5MA 已 distant > 3% | **WATCH**（記 observation，等下次回測）|
| `entryTooExtended` 且漲停 / 近漲停 | **WATCH_ONLY**（族群燈號，不進場但要追蹤）|

#### 怎麼避免「永遠 WAIT」
- Momentum 不設 WAIT 桶。Gate 過了就是 ENTER，過不了就是 WATCH/REJECT，**不允許等盤**。理由：Momentum 拖時間越久越危險。

#### 怎麼避免「漲停 / 近漲停消失」（最關鍵的策略）
1. **漲停打開拉回 → 站回均價 → 再次漲停**：這是經典 Momentum 訊號，必須能進場。實作上：
   - `ChasedHighEntryEngine` 對 Momentum 桶**不啟用** hard block（feature flag `entry.chased-high-gate.momentum_enabled = false`）。
   - 改用 `momentum_score`（包含整理品質 + 量能）作為篩選。
2. **漲停鎖死無法成交**：直接歸入 `sectorIndicator` 桶；明日盤前列入「強勢股觀察」清單（已是現行 `WatchlistRefreshJob` 行為，需確保 sectorIndicator 與 watchlist 串起來）。
3. **`tradabilityTag = "漲幅過大"`**：在 Momentum 桶下，soft penalty 降為 -0.3（不大幅扣分）。

---

### 1.5 三策略對照速查表

| 維度 | Breakout | Pullback | Momentum Continuation |
|---|---|---|---|
| 預期勝率 | 50-55% | 60-65% | 45-50% |
| 平均 RR | 2.5 | 3.0 | 1.5 |
| 持有天期 | 5-10 日 | 5-15 日 | 1-3 日 |
| 最大倉位 | 1.0x | 1.0x | 0.5x（試單）|
| 停損嚴格度 | 中（-5%） | 中（-5%） | 緊（-2.5%） |
| Time stop | 10 日 | 15 日 | 3 日 |
| 是否允許加碼 | 是（拉回 5MA）| 是（站回 5MA） | **否** |

---

## 2. 錯過飆股 tracking 設計

### 2.1 什麼叫 missed rally

**定義**：某檔股票在 `trade_observation` 表被記錄後 N 個交易日內，最大漲幅 ≥ 閾值，但系統當日 decision 不是 ENTER。

| 觀察視窗 | maxReturnPct 閾值 | 標記 |
|---|---|---|
| T+1 | ≥ +4% | minor_missed |
| T+3 | ≥ +8% | normal_missed |
| T+5 | ≥ +12% | significant_missed |
| T+10 | ≥ +20% | major_missed |

### 2.2 哪些 decision 要追蹤

**追蹤對象（須寫 `trade_observation`）**：
- `decision = REJECT` 且 `expected_to_fail = false`（系統覺得可能會錯，主動追蹤）
- `decision = WAIT`（含 wait_pullback / wait_priceGate）
- `decision = WATCH` / `WATCH_ONLY`
- `decision = REJECT` 且 reason ∈ {`reject_price_gate`, `chased_high_block`, `tradability_tag_block`}（這幾條最可能誤殺飆股）
- 所有 shadow paper_trade（已存在，需擴展）

**不追蹤**：
- `decision = ENTER`（已進真倉或 paper_trade，由 PaperTradeService MTM 處理）
- `marketGrade = C` 集體 REST 的全市場 reject（沒意義）
- `HARD_VETOED` 由真實風險紅線觸發（如 NO_STOP_LOSS）

### 2.3 追蹤指標（每筆 observation 記錄）

| 欄位 | 計算時點 | 說明 |
|---|---|---|
| `observed_at` | 寫入時 | 觀察起始 timestamp |
| `decision` | 寫入時 | ENTER/WAIT/WATCH/REJECT/SHADOW |
| `decision_reason` | 寫入時 | 具名 reason code |
| `entry_grade` | 寫入時 | A_PLUS / A / B / SHADOW / WATCH / REJECTED |
| `strategy_type` | 寫入時 | BREAKOUT / PULLBACK / MOMENTUM_CONT |
| `gate_failures` | 寫入時 | JSON：哪些 gate 把它擋下來 |
| `hypothetical_entry_price` | 寫入時 | 若當下進場的價（用日內 VWAP）|
| `hypothetical_stop` / `tp1` / `tp2` | 寫入時 | 同上 |
| `t1_close_pct` | T+1 收盤 | (close - entry) / entry |
| `t1_max_pct` | T+1 收盤 | (dayHigh - entry) / entry |
| `t1_min_pct` | T+1 收盤 | (dayLow - entry) / entry |
| `t3_close_pct` / `t3_max_pct` / `t3_min_pct` | T+3 | 同上 |
| `t5_close_pct` / `t5_max_pct` / `t5_min_pct` | T+5 | 同上 |
| `t10_close_pct` / `t10_max_pct` / `t10_min_pct` | T+10 | 同上 |
| `mfe_pct` | T+10 | 觀察期間最大未實現獲利（Maximum Favorable Excursion） |
| `mae_pct` | T+10 | 觀察期間最大未實現虧損（Maximum Adverse Excursion） |
| `outperform_0050_pct` | T+5 | 個股漲跌 - 0050 同期漲跌 |
| `is_sector_top_performer` | T+5 | 同題材內漲幅排名第 1 |
| `missed_rally_label` | T+5 | minor / normal / significant / major / null |
| `gate_judgment` | T+10 | `correct_reject` / `gate_too_strict` / `inconclusive` |

### 2.4 如何判斷 gate 太嚴 vs reject 正確

**Decision rule（自動）**：

```
IF decision IN (REJECT, WAIT, WATCH) AND missed_rally_label IS NOT NULL:
    IF mae_pct > -3% AND mfe_pct > 8% → gate_too_strict
    ELSE IF mae_pct < -5% AND mfe_pct < 5% → correct_reject_high_volatility
    ELSE → inconclusive

IF decision IN (REJECT, WAIT, WATCH) AND missed_rally_label IS NULL:
    IF mae_pct < -5% → correct_reject
    ELSE → inconclusive
```

**累積到策略層級的判斷**（每週彙總）：

| 指標 | 閾值 | 動作 |
|---|---|---|
| 過去 20 個交易日 `gate_too_strict` 比例 ≥ 30% | 連續 2 週 | 自動建議降低該 gate threshold（仍由人決定）|
| 過去 20 個交易日 `correct_reject` 比例 ≥ 70% | 連續 2 週 | gate 維持或可微調更嚴 |
| reject_price_gate / chased_high_block 命中 missed_rally 連續 ≥ 5 次 | 立即 | 寫一筆 SYSTEM_ALERT LINE 提醒 review |

### 2.5 建議資料模型

```sql
-- 主表
CREATE TABLE trade_observation (
  id                          BIGINT PRIMARY KEY AUTO_INCREMENT,
  observed_date               DATE NOT NULL,
  observed_at                 DATETIME NOT NULL,
  symbol                      VARCHAR(16) NOT NULL,
  stock_name                  VARCHAR(64),
  decision                    VARCHAR(24) NOT NULL,        -- ENTER/WAIT/WATCH/WATCH_ONLY/REJECT/SHADOW
  decision_reason             VARCHAR(255),
  strategy_type               VARCHAR(32),                 -- BREAKOUT / PULLBACK / MOMENTUM_CONT / MIXED
  entry_grade                 VARCHAR(16),                 -- A_PLUS / A / B / WATCH / REJECTED
  gate_failures_json          JSON,                        -- ["chased_high_block","rr_below_min"]
  hypothetical_entry_price    DECIMAL(18,4),
  hypothetical_stop           DECIMAL(18,4),
  hypothetical_tp1            DECIMAL(18,4),
  hypothetical_tp2            DECIMAL(18,4),
  source_task_type            VARCHAR(24),                 -- PREMARKET/OPENING/MIDDAY/POSTMARKET
  final_decision_id           BIGINT,                      -- FK to final_decision
  -- T+N 指標（cron 每日填回）
  t1_close_pct                DECIMAL(8,4),
  t1_max_pct                  DECIMAL(8,4),
  t1_min_pct                  DECIMAL(8,4),
  t3_close_pct                DECIMAL(8,4),
  t3_max_pct                  DECIMAL(8,4),
  t3_min_pct                  DECIMAL(8,4),
  t5_close_pct                DECIMAL(8,4),
  t5_max_pct                  DECIMAL(8,4),
  t5_min_pct                  DECIMAL(8,4),
  t10_close_pct               DECIMAL(8,4),
  t10_max_pct                 DECIMAL(8,4),
  t10_min_pct                 DECIMAL(8,4),
  mfe_pct                     DECIMAL(8,4),
  mae_pct                     DECIMAL(8,4),
  outperform_0050_pct         DECIMAL(8,4),
  is_sector_top_performer     BOOLEAN,
  missed_rally_label          VARCHAR(24),
  gate_judgment               VARCHAR(32),
  finalized_at                DATETIME,                    -- T+10 計算完成時間戳
  created_at                  DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_observed_date (observed_date),
  INDEX idx_symbol_date (symbol, observed_date),
  INDEX idx_decision (decision, observed_date)
);
```

### 2.6 API 與報表

```http
# 新增
POST /api/observations
  Body: { symbol, decision, decisionReason, strategyType, entryGrade, gateFailures[], hypothetical{}, sourceTaskType, finalDecisionId }

# 查詢
GET /api/observations?from=YYYY-MM-DD&to=YYYY-MM-DD&decision=REJECT
GET /api/observations/missed-rallies?days=20    # 過去 N 日 missed_rally_label != null
GET /api/observations/gate-effectiveness?days=20

# 報表（dashboard / weekly review 用）
GET /api/reports/observation-summary?from=...&to=...
  Response: {
    totalCount,
    byDecision: { ENTER: n, WAIT: n, WATCH: n, REJECT: n },
    byStrategy: { BREAKOUT: n, PULLBACK: n, MOMENTUM_CONT: n },
    missedRallyCount,
    missedRallyByLabel: { minor: n, normal: n, significant: n, major: n },
    gateJudgmentDistribution: { correct_reject: n, gate_too_strict: n, inconclusive: n },
    mostFrequentMissReasons: [ { reason, count, avgMissedReturnPct } ]
  }
```

### 2.7 關鍵 Cron Job 增加

| 時間 | Job | 行為 |
|---|---|---|
| 每日 13:35 | ObservationDailyMtmJob | 對所有 `finalized_at IS NULL` 的 observation 計算 T+N 欄位（已過 N 個交易日的 row 才算）|
| 每日 18:30 | ObservationLabelJob | 補 `missed_rally_label` / `gate_judgment` |
| 每週五 19:00 | ObservationWeeklySummaryJob | 寫一筆週報到 `weekly_observation_summary`，由 `WeeklyTradeReviewJob` 同框引用 |

---

## 3. Forward observation 設計

### 3.1 全候選追蹤範圍

**所有候選股都要進 forward observation**，包含但不限於：

| 來源 | 寫入時機 | observation `decision` |
|---|---|---|
| 09:30 FinalDecision selected | 進 trade_observation 同時觸發 paper_trade（已有）| ENTER |
| 09:30 finalDecision rejected/wait/watch | FinalDecisionEngine 內部 trace | REJECT / WAIT / WATCH |
| 15:30 PostmarketAnalysis 候選 | 寫入後等明日進場 / 不進場 | （明日才寫 observation）|
| 17:50 / 08:30 watchlist | 進入 watchlist 即寫 | WATCH |
| paper_trade `is_shadow=true` | 沿用現有事件 listener | SHADOW |
| `tradabilityTag=不列主進場` block | FinalDecisionEngine | REJECT (`tradability_tag_block`) |
| `chased_high_block` | FinalDecisionEngine | REJECT (`chased_high_block`) |
| `priceGate WAIT` | FinalDecisionEngine | WAIT (`reject_price_gate`)（注意是 WAIT 不是 REJECT）|
| `priceGate BLOCK` | FinalDecisionEngine | REJECT (`reject_price_gate_block`) |
| `wait_pullback` | Pullback 策略內部 | WAIT (`pullback_not_yet_recovered`) |

### 3.2 報表 by 維度

#### 3.2.1 by-decision

```
SELECT decision, COUNT(*), AVG(t5_max_pct), AVG(mfe_pct), AVG(mae_pct),
       SUM(CASE WHEN missed_rally_label IS NOT NULL THEN 1 ELSE 0 END) AS missed
FROM trade_observation
WHERE observed_date BETWEEN ? AND ?
GROUP BY decision
```

#### 3.2.2 by-grade

```
SELECT entry_grade, COUNT(*), AVG(t5_close_pct), STDDEV(t5_close_pct),
       AVG(mfe_pct), AVG(mae_pct)
FROM trade_observation
WHERE decision IN ('ENTER','SHADOW') AND observed_date BETWEEN ? AND ?
GROUP BY entry_grade
```

對照組期望：A+ 的 t5_close_pct 平均應顯著高於 A、A 高於 B；若不是，代表評分系統的 grade 分層失效。

#### 3.2.3 by-strategy

```
SELECT strategy_type, decision, COUNT(*), AVG(t5_max_pct), AVG(t10_close_pct)
FROM trade_observation
WHERE observed_date BETWEEN ? AND ?
GROUP BY strategy_type, decision
```

對照組期望：
- BREAKOUT 的 ENTER 應有較高 mfe_pct / 較大 stddev（高波動）
- PULLBACK 的 ENTER 應有較高勝率（t5_close_pct > 0 比例）
- MOMENTUM_CONT 的 ENTER 應 hold 期短但 mfe_pct 收斂

#### 3.2.4 by-gate

```
SELECT JSON_EXTRACT(gate_failures_json, '$[0]') AS first_gate,
       COUNT(*), AVG(t5_max_pct),
       SUM(CASE WHEN gate_judgment='gate_too_strict' THEN 1 ELSE 0 END) AS too_strict
FROM trade_observation
WHERE decision='REJECT'
GROUP BY first_gate
```

最重要的視角：**哪一條 gate 最常誤殺**。

### 3.3 用 20-30 個交易日驗證策略

#### 3.3.1 Phase 命名
- **Phase A 觀察期（前 10 個交易日）**：只蒐集，不調參。確認資料品質、欄位齊全、cron 正常。
- **Phase B 評估期（11-30 個交易日）**：每週五 review，但**不調引擎邏輯**，只調 score_config 數值參數（門檻、權重）。
- **Phase C 落地期（30 日後）**：依數據建議調整策略邏輯（gate 的 hard/soft 分類、新增/拿掉 gate）。

#### 3.3.2 30 日驗證 checklist

| 指標 | 目標 |
|---|---|
| ENTER 數量 / 交易日 | ≥ 0.5（每 2 日至少進 1 檔，避免永遠 REST）|
| ENTER 30 日累積勝率 | ≥ 45%（A+/A 應 ≥ 50%）|
| ENTER 平均 RR realized | ≥ 1.5 |
| missed_rally_count / observation_count | ≤ 25%（超過代表 gate 太嚴）|
| gate_too_strict / gate_judgment_total | ≤ 30% |
| A+ grade 勝率 - B grade 勝率 | ≥ 10 個百分點（grade 分層有效）|
| Breakout / Pullback / Momentum 三策略各自至少 5 次 ENTER | 三策略都要被驗證 |

未達標的處置在第 6 節。

---

## 4. 持倉 trailing stop 規則

### 4.1 分段鎖利（基於 unrealized PnL%）

> 取代現行 `position.trailing.{breakeven,first,second}_pct = 3.0/5.0/8.0` 的三段。

| pnlPct 區間 | 名稱 | stopFloor 設定 | trailing 規則 |
|---|---|---|---|
| < 5% | UNLOCKED | 維持原始進場停損 | 不動 |
| 5% ~ 9.99% | BREAKEVEN | `stop ≥ entryCost`（保本） | trailingPrice = max(currentStop, entry × 1.005) |
| 10% ~ 19.99% | LOCK_5PCT | `stop ≥ entryCost × 1.05` | trailingPrice = max(prev, max(entry × 1.05, 5MA × 0.985)) |
| 20% ~ 29.99% | LOCK_10PCT | `stop ≥ entryCost × 1.10` | trailingPrice = max(prev, max(entry × 1.10, 10MA × 0.985, swingLow × 0.99)) |
| ≥ 30% | LOCK_20PCT | `stop ≥ entryCost × 1.20` | trailingPrice = max(prev, max(entry × 1.20, 10MA × 0.985, swingLow × 0.99, currentPrice − 1.5 × ATR(20))) |

#### 設定 keys（落入 score_config）

```
position.trailing.tier1_pnl_threshold = 0.05
position.trailing.tier1_stop_floor_pct = 0.005       # BREAKEVEN +0.5%

position.trailing.tier2_pnl_threshold = 0.10
position.trailing.tier2_stop_floor_pct = 0.05        # LOCK_5%

position.trailing.tier3_pnl_threshold = 0.20
position.trailing.tier3_stop_floor_pct = 0.10        # LOCK_10%

position.trailing.tier4_pnl_threshold = 0.30
position.trailing.tier4_stop_floor_pct = 0.20        # LOCK_20%

position.trailing.use_5ma                = true
position.trailing.use_10ma               = true
position.trailing.use_atr_multiple       = 1.5
position.trailing.use_swing_low_lookback = 5
```

### 4.2 5MA / 10MA / ATR / swing low 補強規則

#### 5MA
- tier ≥ LOCK_5PCT 啟用
- 計算：當日收盤價的 5 日 SMA（盤中暫用前一日 5MA）
- buffer：5MA × 0.985（防洗盤觸發停損）

#### 10MA
- tier ≥ LOCK_10PCT 啟用
- 同 5MA 邏輯，10 日 SMA

#### ATR(20)
- tier = LOCK_20PCT 啟用，且只在「持倉超過 5 日」時才用
- `trailingPrice = currentPrice − ATR(20) × 1.5`
- 防止「飆股拉回正常震盪」被誤觸停損

#### Recent swing low
- tier ≥ LOCK_10PCT 啟用
- 取最近 5 個交易日 dayLow 的最低值
- buffer：× 0.99
- 用於 Pullback / Momentum，因為這類股票會出現多次「拉回 swing low 再上攻」

#### 多訊號取最大值
- 各信號都產出一個 candidate stop，最終 trailingPrice = `max(所有 candidate)`
- 確保 stop 只會往上不會往下

### 4.3 TP1 / TP2 後 stop policy

| 階段 | 倉位處置 | 剩餘部位 stop |
|---|---|---|
| 達 TP1（+6 或 +8%） | 賣 1/3，剩 2/3 | stop 上移到「進場成本 + 1%」（保本帶獲利）|
| 達 TP2（+12 或 +15%） | 再賣 1/3，剩 1/3 | stop 上移到 entry × 1.05；同時改用 5MA trailing |
| 達 TP3（+25%）| 再賣 1/4 ~ 1/3，剩 1/4 | stop = max(entry × 1.10, 10MA × 0.985) |
| 達 TP4（+40%）| 看心情續抱或全出 | stop = entry × 1.20 |

**注意**：TP 達標後賣出由 Austin 手動執行，系統只負責通知 + 寫 review log。`paper_trade` 模擬全出（沿用 P0.6 行為）。

### 4.4 quote stale / null 時如何處理

> 沿用現有 `PositionReviewService` 規則並擴展。

| 情境 | 行為 |
|---|---|
| `quote == null`（盤外 / API 失敗） | review log 寫 reason=「即時報價不可用」、status=HOLD、**不更新** trailingStopPrice |
| `quote.currentPrice == null` 但有 prevClose | 同上，視為不可用 |
| quote 過期（last update > 10 min） | 視為 stale；review log 標 `stale_quote=true`，status 強制 HOLD，不觸發 EXIT 訊號 |
| quote ≥ stop 但 stop 是 5MA 計算來的 + 5MA 資料缺 | 不執行 EXIT，標 `stop_data_incomplete`，等下一輪 review |

**Hard rule**：若 trailing 訊號之一缺資（如 5MA），其他訊號照算，但需在 review log 標明哪些訊號被略過，避免錯誤升級 stop。

### 4.5 reviewStatus 優先級（避免 EXIT 被 STRONG/HOLD 覆蓋）

#### 現況問題
`PositionDecisionEngine.evaluate` 從上到下走 if/else，理論上 EXIT 優先；但下游 `PositionReviewService.applyOverride` 透過 `ExitRegimeIntegrationEngine` 可能再覆蓋一次，又 5 分鐘 monitor 反覆呼叫導致 status 在 EXIT/HOLD 之間跳動。

#### 設計：reviewStatus 優先級表

| 優先級 | status | 規則 |
|---|---|---|
| 1（最高，sticky）| EXIT | 一旦發出 EXIT，**24 小時內**不會被 HOLD/STRONG/WEAKEN/TRAIL_UP 覆蓋；只能由 Austin 手動關倉或 sticky 過期 |
| 2 | TRAIL_UP | 每輪都可重算；只能往上不能往下（trailingStopPrice monotonic increase） |
| 3 | WEAKEN | 從 STRONG/HOLD 可降到 WEAKEN；從 WEAKEN 升到 STRONG 需連續 3 輪都符合 isStrong |
| 4 | HOLD | default |
| 5 | STRONG | 需連續 3 輪 isStrong=true 才升級，避免單次正面就升 STRONG |

#### 實作要點
- `PositionEntity` 新增欄位：`exit_signal_at` (DATETIME)，記 EXIT 第一次發出時間
- `position_review_log` 寫入新欄位 `effective_status`（最終生效的 status，可能跟本輪 raw status 不同）
- `PositionReviewService` 計算 raw status 後，依優先級決定 effective_status，再寫 entity

#### sticky EXIT 解除規則
- 24 小時自動過期（避免永久卡住）
- Austin 透過 `POST /api/positions/{id}/clear-exit-sticky` 手動清除
- position close 後當然清除

---

## 5. Dashboard / notification 語意設計

### 5.1 四層狀態定義

> **核心訴求**：UI 與 LINE 不得把 monitor `WATCH` 顯示成 trade `ENTER`。

#### 5.1.1 marketState（市場層）
- **負責**：MarketRegimeService / MarketGradeService
- **可能值**：`A` / `A_PLUS` / `B` / `C` / `BULL_TREND` / `WEAK_DOWNTREND` / `PANIC_VOLATILITY` / `RANGE_BOUND`
- **更新頻率**：每小時 + 每次 finalDecision 觸發
- **語意**：「今天大盤適不適合做」
- **顯示位置**：dashboard 頂部 banner、LINE 每則訊息開頭

#### 5.1.2 monitorState（盤中監控層）
- **負責**：FiveMinuteMonitorJob / HourlyIntradayGateJob
- **可能值**：`OFF` / `WATCH` / `ACTIVE` / `ALERT_ONLY`
- **更新頻率**：每 5 分鐘
- **語意**：「現在系統在不在盯盤」
  - `OFF`：marketGrade=C 或 11:00 後無持倉，停止 5 分鐘 LINE 噪音
  - `WATCH`：有候選但無持倉，每整點才發訊息
  - `ACTIVE`：有持倉或 ENTER 訊號，5 分鐘檢查並可能發 LINE
  - `ALERT_ONLY`：有持倉但 reviewStatus=EXIT 已發過，不再重發
- **顯示位置**：dashboard 第二行 / LINE 訊息「📡 監控狀態」段

#### 5.1.3 tradeDecision（今日是否進場層）
- **負責**：FinalDecisionEngine（盤中模式）
- **可能值**：`ENTER` / `WAIT` / `REST`
- **更新頻率**：09:30 主要決策 + 各時段 re-evaluate
- **語意**：「今天系統的最終建議」
- **顯示位置**：dashboard 中央 / LINE 訊息標題

#### 5.1.4 finalDecision（哪幾檔層）
- **負責**：FinalDecisionService 持久化（`final_decision` 表）
- **可能值**：含 `selected_stocks[]`、各檔 `entry_grade`、`strategy_type`、`hypothetical_stop` 等
- **更新頻率**：09:30 寫一筆，後續修正寫新版本（version+1）
- **語意**：「具體哪幾檔以什麼價位進場」
- **顯示位置**：dashboard 表格 / LINE 訊息列表

### 5.2 四層之間的硬規則

```
IF marketState = C:
    monitorState = OFF
    tradeDecision = REST
    finalDecision.selected_stocks = []

IF tradeDecision = WAIT:
    finalDecision.selected_stocks = []  ← 不得有任何「待進場標的」
    monitorState 可以是 WATCH 或 ACTIVE（看是否有持倉）

IF tradeDecision = ENTER:
    finalDecision.selected_stocks 必須有 ≥ 1 檔
    monitorState = ACTIVE

IF monitorState = WATCH AND tradeDecision != ENTER:
    LINE / UI 上「監控中：xxx 檔」**不得**顯示為「建議買進 xxx 檔」
```

### 5.3 REST 時仍要顯示的內容

REST 不等於沒事做。REST 時 dashboard / LINE 仍應顯示：

```
🔴 今日 tradeDecision = REST（reason=連續 3 日 marketGrade C）
─────
📊 marketState：C（breadth=-2.1%, vix_proxy 升高）
📡 monitorState：OFF
─────
📈 持倉狀態（即使 REST 仍要追蹤）：
  - 2330 +12.3% [LOCK_5PCT] stop=910 / TP1 done / 5MA=931
  - 6505 -1.8% [UNLOCKED] stop=42.5 / 持有 4 日

🔍 三策略當下狀態：
  - Breakout：0 候選達 A 門檻；最高分 7330 分數 6.8
  - Pullback：1 候選 wait_pullback（2454 等站回 5MA）
  - Momentum：0 候選；強勢族群 AI / 重電當前已 sectorIndicator

📋 過去 5 日累積：ENTER 1 / WAIT 4 / REST 0；missed_rally 1 檔（2376 +9.2% T+3）
```

設計重點：**REST 不靜默**。Austin 看完 LINE 後可立刻判斷「系統合理空手」vs「系統壞掉」。

### 5.4 LINE 訊息模板（升級版）

```
[Trading System｜09:30 FinalDecision]
🔵 marketState=A   📡 monitorState=ACTIVE
🟢 tradeDecision=ENTER（2 檔）

▎主攻 1：6770 力積電 [BREAKOUT｜A+]
  Entry 25.6-26.0  Stop 24.3 (-5.0%)
  TP1 27.5  TP2 29.4  RR 2.6
  Strategy: 突破近 60 日新高 + 量增 2.1x
  族群：記憶體（rank 1, score 8.5）

▎主攻 2：3017 奇鋐 [PULLBACK｜A]
  Entry 1320-1340  Stop 1265 (-4.8%)
  TP1 1420  TP2 1520  RR 2.9
  Strategy: 回測 5MA 量縮，相對強度高

📈 持倉：
  - 2330 +14.2% [LOCK_5PCT] stop=915
  - 6505 -2.1% [UNLOCKED] stop=42.5

🔍 觀察池（今日不進但會追蹤）：
  - 2376 [WATCH｜B] 待續強
  - 2454 [WAIT_PULLBACK] 等站回 5MA

來源：Trading System
```

---

## 6. 配置與驗證建議

### 6.1 每個策略可調參數（score_config keys）

#### 全域
```
trading.status.allow_trade                 = true
strategy.classifier.priority               = "PULLBACK,BREAKOUT,MOMENTUM_CONT"
strategy.consecutive_rest.max_days         = 5         # 連續空手幾日後降門檻
strategy.consecutive_rest.score_relax      = 0.3       # 每階段降 0.3 分
```

#### Breakout
```
breakout.score.weight.pattern              = 0.25
breakout.score.weight.volume               = 0.20
breakout.score.weight.rr                   = 0.20
breakout.score.weight.sector               = 0.15
breakout.score.weight.institutional        = 0.10
breakout.score.weight.market_grade         = 0.10
breakout.gate.min_volume_ratio             = 1.3
breakout.gate.max_extension_pct            = 0.05
breakout.entry.min_rr                      = 2.0
breakout.entry.target_rr_for_aplus         = 2.5
breakout.entry.max_chase_pct               = 0.02
breakout.stop.pct                          = 0.05      # -5%
```

#### Pullback
```
pullback.score.weight.position_quality     = 0.30
pullback.score.weight.volume_quality       = 0.20
pullback.score.weight.relative_strength    = 0.15
pullback.score.weight.float_stability      = 0.15
pullback.score.weight.rr                   = 0.10
pullback.score.weight.theme                = 0.10
pullback.gate.max_holding_days_since_breakout = 20
pullback.entry.min_rr                      = 2.5
pullback.entry.target_rr_for_aplus         = 3.0
pullback.entry.split_position_first_pct    = 0.5
pullback.wait.max_days                     = 2
pullback.stop.pct                          = 0.05
```

#### Momentum Continuation
```
momentum.score.weight.consolidation        = 0.25
momentum.score.weight.volume_expand        = 0.20
momentum.score.weight.theme_leader         = 0.20
momentum.score.weight.market_strength      = 0.15
momentum.score.weight.entry_timing         = 0.10
momentum.score.weight.float_persistence    = 0.10
momentum.entry.min_rr                      = 1.5
momentum.entry.min_score                   = 7.0
momentum.entry.chased_high_gate_enabled    = false
momentum.tradability_tag.soft_penalty      = 0.3
momentum.stop.pct                          = 0.025
momentum.max_holding_days                  = 3
momentum.max_picks                         = 1
```

#### Trailing
（已列於 §4.1，不重複）

#### Observation
```
observation.enabled                        = true
observation.windows.t1                     = 1
observation.windows.t3                     = 3
observation.windows.t5                     = 5
observation.windows.t10                    = 10
observation.missed_rally.t1_threshold      = 0.04
observation.missed_rally.t3_threshold      = 0.08
observation.missed_rally.t5_threshold      = 0.12
observation.missed_rally.t10_threshold     = 0.20
observation.gate_too_strict.mae_max        = -0.03
observation.gate_too_strict.mfe_min        = 0.08
observation.weekly_alert.too_strict_pct    = 0.30
```

### 6.2 不要自動下單

**設計約束**：
1. `paper_trade` 表跟 `position` 表必須完全分開；shadow 不得寫進 `position`。
2. ENTER 訊號只發 LINE + 寫 `final_decision`；**不**呼叫任何 broker API。
3. 任何「自動平倉」都只能寫 `paper_trade`（已是現行 P0.6 行為）；要動真倉必須是 Austin 手動 `POST /api/positions/{id}/close` 或經過明示開關 `position.review.auto_close.paper_only=false`（預設 true）。
4. observation 寫入 hypothetical_entry_price 等欄位**不得**作為任何下單依據；只用於回測驗證。

### 6.3 shadow / observation 不得偽裝成 live decision

**Hard rules**：
- `paper_trade.is_shadow = true` 的 row：
  - 不寫 `position` 表
  - 不發任何含「進場」「買進」字樣的 LINE
  - 在 dashboard 顯示時必須有明顯 `[SHADOW]` 前綴
  - 不計入 `daily_pnl.csv`
- `trade_observation.decision != 'ENTER'` 的 row：
  - 完全不影響 `final_decision` 表
  - LINE 顯示時必須在「🔍 觀察池」段，**不得**進「主攻」段
- `final_decision.aiStatus = 'PARTIAL_AI_READY'` 或 `paper_only=true` flag 設定時：
  - LINE 訊息必須加 `⚠ Shadow 模式` 標記

### 6.4 需要的 test cases

#### 6.4.1 Strategy classification
- `StrategyClassifierTest`：給定 candidate JSON（含突破天數、回測位置、整理時間），驗證分到正確策略
- 邊界：剛好 10 日突破（Breakout vs Momentum 邊界）、跌破 5MA 後又站回（Pullback 復活）

#### 6.4.2 漲停 / 近漲停不消失
- `ChasedHighMomentumIntegrationTest`：模擬 dayHigh × 0.99 的 currentPrice，Momentum 桶必須通過，Breakout 桶可分流
- `LockedLimitUpTest`：漲停鎖死無成交價，必須進 sectorIndicator 不 BLOCK

#### 6.4.3 永遠 WAIT 防護
- `ConsecutiveRestRelaxTest`：連續 5 日 REST 後第 6 日門檻自動降 0.3，原本邊界候選必須能進 ENTER
- `WaitTimeoutDowngradeTest`：09:30 WAIT 候選到 10:30 仍未轉 ENTER → 自動降 WATCH

#### 6.4.4 Trailing tier 升級
- `TrailingTierProgressionTest`：pnlPct 從 4% → 5% → 10% → 20% → 30% 依次驗證 stopFloor 跳到對應的 tier
- `TrailingMonotonicTest`：trailingStopPrice 只能往上，不能因 5MA 拉回而下調
- `TrailingMissingDataTest`：5MA 缺資時其他訊號照算，stop 不誤升

#### 6.4.5 reviewStatus sticky
- `ExitStickyTest`：發過 EXIT 後 24 小時內，即使 isStrong=true 也維持 EXIT
- `ExitStickyExpireTest`：> 24 小時後且 Austin 未平倉，狀態可重評（但通常 Austin 應已平）

#### 6.4.6 Observation lifecycle
- `ObservationDailyMtmTest`：給定 entry date + 5 日 quote，T+1/T+3/T+5 欄位正確填入
- `ObservationLabelTest`：mfe=10%, mae=-2% → gate_too_strict
- `ObservationApiTest`：`GET /api/observations/missed-rallies?days=20` 回傳正確列表

#### 6.4.7 Dashboard 語意
- `DashboardSemanticIntegrationTest`：marketState=C → monitorState=OFF + tradeDecision=REST + finalDecision.selected=[]
- `LineMessageBuilderShadowTagTest`：shadow 訊號必須有 `[SHADOW]` 標記

#### 6.4.8 持倉 quote stale
- `QuoteStaleHoldOnlyTest`：quote.timestamp 超過 10 分鐘 → status 強制 HOLD，不發 EXIT

---

## 7. Phase 實作優先順序

> 給 Codex 的實作順序建議，依「對使用者體感影響大 + 風險低」排序。每 Phase 預估工作量（估計值）。

### Phase 0：資料與觀察基礎（前置，~3 工作日）

1. 建 `trade_observation` 表（migration）
2. `ObservationService` + `POST /api/observations` 寫入端點
3. `FinalDecisionEngine` 在 reject/wait/watch 三條 path 加 hook，呼叫 ObservationService
4. `ObservationDailyMtmJob`（13:35）+ `ObservationLabelJob`（18:30）
5. `GET /api/observations` 系列 API + `GET /api/reports/observation-summary`

**驗收**：跑一週後 `trade_observation` 應有 ≥ 50 筆 row，T+1 欄位都有填。

### Phase 1：策略分流框架（~5 工作日）

1. 新增 `StrategyType` enum 補上 `BREAKOUT` / `PULLBACK` / `MOMENTUM_CONT`（保留 `SETUP` 作為 legacy fallback）
2. 新增 `StrategyClassifier` service：依 `突破天數`、`回測位置`、`整理時間` 等欄位分類
3. `FinalDecisionCandidateRequest` 帶 `strategyHint` 欄位（Codex / PowerShell screener 提供）
4. `FinalDecisionEngine` 走分流：依策略選對應的 gate / RR / score weights
5. `BreakoutScoringEngine` / `PullbackScoringEngine` / `MomentumContinuationScoringEngine` 三個獨立評分元件
6. ChasedHigh 改為 strategy-aware：Momentum 桶不啟用 hard block

**驗收**：不同 strategyType 的候選走不同 gate；現有測試（SETUP）全部不受影響。

### Phase 2：永遠 WAIT 與漲停消失修正（~3 工作日）

1. WAIT timeout downgrade（10:30 後 WAIT → WATCH）
2. 連續 REST 自動降門檻（連 5 日 -0.3）
3. 漲停 / 近漲停分流到 sectorIndicator + WATCH_ONLY，不 hard block
4. `tradabilityTag` soft penalty 依 strategy 不同

**驗收**：模擬連續 5 日 marketGrade=B 但無 ENTER → 第 6 日門檻降低，能輸出至少 1 檔 ENTER（若有候選）。

### Phase 3：分段鎖利持倉風控（~4 工作日）

1. `PositionDecisionEngine` 把 trailing 從三段擴成五段（UNLOCKED / BREAKEVEN / LOCK_5/10/20PCT）
2. `TrailingComputer` 服務：吃 5MA / 10MA / ATR / swing low 多訊號
3. `position.exit_signal_at` 欄位 + sticky EXIT 規則
4. `position_review_log.effective_status` 欄位 + 優先級邏輯
5. quote stale 偵測（last_update > 10 min）

**驗收**：模擬 +8% / +12% / +22% / +35% 四種 pnl，trailingStopPrice 進入對應 tier；EXIT 後 1 小時 isStrong=true 不覆蓋。

### Phase 4：Dashboard / LINE 語意分層（~3 工作日）

1. Dashboard API `GET /api/dashboard/current` 回傳結構化 4 層
2. `LineMessageBuilder` 每條訊息開頭固定四層 banner
3. shadow / observation 訊息加 `[SHADOW]` / 「🔍 觀察池」獨立段
4. REST 時的 dashboard 內容（持倉 / 三策略狀態 / 過去 5 日累積）

**驗收**：marketGrade=C 時 LINE / UI 不會出現任何「建議買進」字樣；shadow 訊號明確標示。

### Phase 5：Forward observation 報表與弱優化建議（~4 工作日）

1. `WeeklyTradeReviewJob` 引用 `weekly_observation_summary`
2. 自動產生「過去 20 日 gate 太嚴 top 3」建議
3. `gate_judgment` 命中過多時 SYSTEM_ALERT LINE
4. 30 日驗證 checklist 自動評估 + dashboard 顯示

**驗收**：跑滿 30 日後可從 `GET /api/reports/observation-summary?from=...&to=...` 看到 7 項 checklist 是否達標，並列出弱優化建議（不自動 apply）。

### Phase 6：策略參數可調與 A/B（~4 工作日）

1. 所有 §6.1 列的 score_config keys 全部落地、有 default
2. `score_config_change_log` 表記錄每次調整
3. `ScoreConfigService` 加 audit log（誰、何時、改了什麼）
4. shadow vs live A/B：相同候選同時跑兩套參數，30 日後對照

**驗收**：可以 `PUT /api/score-config/breakout.entry.min_rr` 從 2.0 改到 1.8，立刻反映在下一輪 finalDecision。

---

## 8. 開放議題（待 Austin 確認）

1. **是否允許 strategy-aware veto**：Pullback 強勢股的 RR < 2.5 是否完全不收，或允許試單？
2. **加碼是否系統化**：本設計把加碼歸入 Breakout / Pullback，但 Austin 過去多手動。是否要 LINE 主動建議加碼點？
3. **multi-strategy 同檔股票**：若一檔股票同日同時符合 Pullback 與 Breakout（剛突破又回測），目前優先 Pullback。是否 OK？
4. **observation T+10 後是否清除**：`trade_observation` 30 日後資料量會大，是否定期 archive 到 `trade_observation_archive`？
5. **手動 override grade**：Austin 看好但系統判 B → 是否提供 `POST /api/decisions/override` 強制 ENTER？（會破壞統計純度）

---

## 附錄 A：本設計與現行 code 的對應

| 現行檔案 | 本設計變更 |
|---|---|
| `FinalDecisionEngine.java` | 加 strategy 分流；ChasedHigh 改 strategy-aware；連續 REST 降門檻；WAIT timeout |
| `PriceGateEvaluator.java` | 不變動主邏輯；只是 trace 多寫到 observation |
| `VetoEngine.java` | 加 BREAKOUT / PULLBACK / MOMENTUM_CONT 三條分支（沿用 SETUP / MOMENTUM_CHASE 雛形） |
| `PositionDecisionEngine.java` | 三段 trailing 擴成五段；effective status 優先級 |
| `PositionReviewService.java` | sticky EXIT；quote stale 偵測；trailing 用新 TrailingComputer |
| `PaperTradeService.java` | shadow 維持；新增 strategy_type 欄位；不變 auto-exit |
| `FinalDecisionService.java` | 寫 observation；finalDecision payload 多 strategy_type |
| `LineMessageBuilder` | 四層 banner；shadow / observation 段 |
| 新增 | `ObservationService` / `StrategyClassifier` / `BreakoutScoringEngine` / `PullbackScoringEngine` / `MomentumContinuationScoringEngine` / `TrailingComputer` |

## 附錄 B：與既有 v2.x 文件的關係

- 本設計**不取代** `docs/scoring-workflow.md`（評分管線總覽）；scoring-workflow 維持 Java→Claude→Codex→Consensus 的計算流程，本設計只是把「最後 FinalDecision 的決策邏輯」分成三策略。
- 本設計**取代** `docs/momentum-chase-strategy-design.md`（Momentum 部分）的 v0.x 內容；Momentum Continuation 是 momentum-chase 的演進版。
- 本設計**新增** `trade_observation` 領域，是 `paper_trade` 的補充：paper_trade 追蹤 ENTER 樣本，observation 追蹤 ENTER + 所有「沒進場」樣本。

---

**結束。等 Austin / Codex review。**
