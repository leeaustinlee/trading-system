# Production Trading Reality Check + Session-Aware Redesign

> 提交給 Codex 做架構裁決。  
> Claude 的角色：提出問題 + 具體可執行建議。Codex 的角色：選擇保留/放棄/修改哪些，並定實作優先順序。

**版本**：v2.7 Planning  
**前置**：v2.6 MVP Refactor（commit `dc7e99d`）已完成，但**實盤行為仍接近不下單**。  
**撰寫日期**：2026-04-22（台北時間 13:00 盤中產出）  
**作者**：Claude（5 角色聯合審查）  
**背景**：今日 4/22 主升段（加權 +0.87%、台積電 +0.98%、2454 聯發科 +9.09%），系統 0 檔進場 → 促發本次審查

---

## 1. 目的

在 v2.6 MVP Refactor 完成後（A+ only → 三層分級、VetoEngine hard/soft 分流、MarketRegime confidence），系統**表面上放寬了**，但實戰觀察（2026-04-22）顯示：

- 主升段 4/22 全候選 `isVetoed=true / finalRank=0`
- 系統 FinalDecision 全部 REST（decision_lock + portfolio_full）
- 即使解除上述 hard block，MVP 後的 consensus_score + penalty 組合仍會把主流龍頭打到 C bucket

**本次審查目的**：
1. 找出為什麼 v2.6 還是不下單
2. **附：發現並修復 Session-Aware 時間分層問題（試撮資料污染決策）**
3. 規劃 v2.7 方向，由 Codex 選擇落地哪些

---

## 2. 五角色聯合審查結論

以下以「主力操盤手 / 短線交易員 / 量化策略設計師 / 風控主管 / 交易檢討教練」五種角色綜合評估。

### 2.1 五問直答

| # | 問題 | 誠實答案 |
|---|------|---------|
| 1 | 能交易還是只會分析？ | **會分析，但實際幾乎下不了單** |
| 2 | 條件太完美 → 進不了場？ | **是。MVP 只修掉 A+ only 表面病，consensus_score + NOT_IN_FINAL_PLAN + RR_BELOW_MIN 連鎖把候選打死** |
| 3 | 主升段會錯過？ | **會。2026-04-22 實證：2454/6770/8039 盤前 +9% → 系統 0 檔進場** |
| 4 | 震盪市過度保守？ | **是。portfolio_full 時無強替換邏輯 → 持倉用不滿又換不動** |
| 5 | 3 個月預測？ | **40% 機率輕虧、25% 打平、15% 小賺、5% 穩定賺錢、15% 大虧** |

### 2.2 最致命的 3 個發現

#### ① Consensus Score 把 Java 結構分拉進共識 → 扭曲強股分數

**算法**：`consensus = min(java, claude, codex) − |java-claude|×0.25 − |java-codex|×0.2 − |claude-codex|×0.2`

**今日 4/22 實例 — 聯發科 2454**：
- java=5.2（結構分，反映 RR 計劃完整性等）
- claude=9.0、codex=8.0（AI 評價真實強弱）
- min = 5.2
- 分歧懲罰 = 0.95 + 0.56 + 0.2 = 1.71
- **consensus = 5.2 − 1.71 = 3.49**
- final = min(aiWeighted=6.95, consensus=3.49) = **3.49**
- 扣 penalty ≈ 2.0（RR_BELOW_MIN + NOT_IN_FINAL_PLAN + ENTRY_TOO_EXTENDED）
- **final 扣分後 ≈ 1.49 → C bucket → REST**

**根本問題**：**Java 結構分跟 AI 評價是不同維度**，硬拉到共識層會把「AI 雙高但結構分中等」的強股打死。

**這不是參數問題，是算法結構問題**。調任何 config 都救不回來。

#### ② Consensus + 3 個 Penalty 連鎖幾乎必然觸發

**候選股進入 FinalDecision 時的常態**：
- `includeInFinalPlan=false` → **PENALTY:NOT_IN_FINAL_PLAN（0.5）** ← 因為 final plan 是 Codex 最終圈選才會 true，candidate 建立當下本來就 false
- `riskRewardRatio=1.33` < 1.8 → **PENALTY:RR_BELOW_MIN（1.5）** ← RR 用靜態 entry 算，幾乎必然不夠
- 強勢股多半 extended → **PENALTY:ENTRY_TOO_EXTENDED（1.0）**

**合計 penalty = 3.0**，任何 raw rank 扣完都難進 B bucket（需 final ≥ 6.8）。

**結論**：v2.6 把 hard veto 改 soft penalty 的設計**沒有實質放寬**，只是把「一次死」改成「三刀慢死」。

#### ③ Regime 判斷 / AI 研究 / 進場決策三者完全脫鉤

- `MarketRegimeEngine` 辛苦算出 `BULL_TREND` / `confidenceLevel=HIGH` / `riskMultiplier=1.0`
- `FinalDecisionEngine` 只讀 `marketGrade` 字串（舊介面），**完全沒消費 regimeType / confidence**
- Regime 說「主升可加碼」跟下游決策毫無連動

**具體漏洞**：BULL_TREND 主升段，FinalDecisionEngine 的 `grade_b_min=6.8` 門檻不會放寬；PANIC_VOLATILITY 時 `grade_ap_min=8.5` 也不會拉嚴。regime 變成純記錄，不是決策輸入。

---

## 3. 致命問題 Top 10（排序：「讓你賺不到錢」）

| # | 問題 | 模組 | 為什麼賺不到 | 嚴重 |
|---|------|------|------|------|
| 1 | `consensus_score` 把 java 結構分跟 AI 分硬拉同一維度 | ConsensusScoringEngine | 強股 AI 雙高但 java 低 → 共識被扭曲成低分 → bucket C | 🔴 |
| 2 | `decision_lock=LOCKED` 無自動解除 | TradingStateService | 一旦觸發整天甚至整週 REST，4/22 就是這狀態 | 🔴 |
| 3 | `PENALTY:NOT_IN_FINAL_PLAN` 常態觸發 | VetoEngine | 候選進 FinalDecision 時 `includeInFinalPlan` 幾乎都 false，等於每檔先扣 0.5 | 🔴 |
| 4 | `PENALTY:RR_BELOW_MIN` 常態觸發 | VetoEngine + Candidate RR 計算 | RR 用靜態 entry 算出來永遠不夠 1.8 | 🔴 |
| 5 | `ENTRY_TOO_EXTENDED` 討厭強股 | VetoEngine | 主升段的主流股本來就 extended → 扣 1 分 → 主流股被壓到 B/C | 🔴 |
| 6 | 沒強替換邏輯 | PortfolioRiskEngine | 滿倉時新強股進不來，最弱持倉又不踢 | 🟡 |
| 7 | MarketRegime 不傳進 FinalDecision | FinalDecisionEngine | regime=BULL_TREND 時邏輯不放寬，跟 RANGE_CHOP 一樣嚴 | 🟡 |
| 8 | FinalDecisionEngine 要求 `entryTriggered=true` + `mainStream=true` | FinalDecisionEngine | 又一層 AND 過濾，補漲/輪動機會全砍 | 🟡 |
| 9 | 沒有動態加碼機制 | PositionService | 強續強、浮盈 +5% 後不加碼 → 主升段漲了也只吃第一段 | 🟡 |
| 10 | `mainStream` / `falseBreakout` 黑白判斷太粗 | FinalDecisionEngine + candidate input | 洗盤一下就觸發，非主流事件股直接排除 | 🟡 |

---

## 4. 三個月實盤預測（慘烈）

如果 v2.6 現狀直接用來實盤 3 個月：

| 結果 | 機率 | 原因 |
|------|-----:|------|
| 穩定賺錢 | 5% | 需主升+無 lock+候選過 bucket 完美重合，幾乎不可能連續 |
| 小賺（<5% 年化） | 15% | 偶爾進場 + 持倉槓桿 ETF 幫忙 |
| 打平 | 25% | 只有 00631L 正二作大盤部位，系統單檔常停損出場 |
| **輕虧（-5~-10%）** | **40%** | **進場頻率低 + 選到平庸 B 股 + 沒加碼 → 小停損吃掉多次小利潤** |
| 大虧（-10% 以上） | 15% | decision_lock 未解時持倉遇大跌，沒動態減碼 |

### 最可能劇本

> **第 1 個月**：系統多數天 REST。Austin 看盤發現 2454 / 6770 漲了，手動進場。系統沒動。  
> **第 2 個月**：系統選到 1~2 檔 B 等級股，盤中洗盤 `belowOpen=true` 停損出場。Austin 意識到「系統挑到弱的」。  
> **第 3 個月**：Austin 開始繞過系統純手動，系統變成「看候選股的工具」而非「下單引擎」。

---

## 5. 🆕 Session-Aware 時間分層（新發現）

### 5.1 問題

**現行系統在 08:30-09:00 期間使用試撮資料做 scoring 與 decision。這是不正確的。**

台股 08:30-09:00 是試撮時段：
- 掛單隨時可撤
- 價格不具真實成交意義
- 容易出現假突破 / 假跌破
- **不可用於交易決策**

實際後果：
- 今早 08:41~08:50 我作為 Claude 做 PREMARKET 研究時用的是 08:45 試撮 → `currentPrice` bid/ask 擾動大
- `ENTRY_TOO_EXTENDED`、`belowOpen`、`mainStream`、`volumeSpike` 這些布林 flag 用試撮價判斷會失真
- 如果 Scheduler 更早（如 PremarketDataPrepJob 08:10）甚至會用**前一日收盤**，更無意義

### 5.2 目標架構

把交易日拆為三個明確階段，每階段允許什麼、禁止什麼：

#### Phase 1: PREMARKET（08:30–09:00）

**允許**：
- 國際市場分析（美股、SOX、ADR、台指期）
- 預期市場偏向（`market_bias`：`BULLISH` / `BEARISH` / `NEUTRAL`）
- 預期主流族群（Claude 研究 thesis）
- **候選清單**（名單可定，但不評分）

**禁止**：
- `FinalDecisionEngine.evaluate` 產出 ENTER / REST
- 進場 / 出場決策
- 使用試撮價做 `priceBreakHigh` / `volumeSpike` / `belowOpen` 判斷
- 使用試撮價算 `riskRewardRatio`

**輸出**：
- `MarketBias` DTO（不是 `MarketGrade`）
- `candidates`（symbols + themes，無 scoring）
- `claude-research-request.json` 內容 for Claude

#### Phase 2: OPEN_VALIDATION（09:00–09:30）

**允許**：
- 用**真實成交資料**驗證開盤是否符合 premarket 預期
- 更新 `candidate scoring`（使用 09:00 後成交價）
- 更新 `MarketRegime`（用真實 breadth / 龍頭強弱）
- **為 09:30 決策準備完整 snapshot**

**禁止**：
- `FinalDecisionEngine.evaluate` 產出 ENTER / REST
- 大量下單（例外：停損觸發可執行）

**輸出**：
- 完整 `market_snapshot`（真實成交）
- 每檔 candidate 的真實 `openPrice` / `dayHigh` / `dayLow` / `currentPrice`
- `MarketRegimeDecision`（HIGH confidence）

#### Phase 3: LIVE_TRADING（09:30 之後）

**允許**：
- `FinalDecisionEngine` 完整執行（A+/A/B bucket）
- Veto / Penalty / Ranking 完整管線
- 進場 / 出場 / 持倉動態調整
- 5 分鐘監控 / 整點 Gate

---

### 5.3 新增 enum

```java
package com.austin.trading.domain.enums;

public enum MarketSession {
    PREMARKET,        // 08:30-09:00，僅允許 bias + 候選清單
    OPEN_VALIDATION,  // 09:00-09:30，允許真實資料 scoring
    LIVE_TRADING;     // 09:30 之後，允許完整決策

    public static MarketSession fromTime(java.time.LocalTime now) {
        if (now.isBefore(java.time.LocalTime.of(9, 0)))  return PREMARKET;
        if (now.isBefore(java.time.LocalTime.of(9, 30))) return OPEN_VALIDATION;
        return LIVE_TRADING;
    }
}
```

### 5.4 必須修改

| 模組 | 修改 | 規則 |
|------|------|------|
| `FinalDecisionEngine` | 進場 `evaluate()` 前先檢查 `MarketSession.fromTime(now)` | `!= LIVE_TRADING` 時直接回 `decision="WAIT"`（新 decision type），不產 ENTER/REST |
| `MarketRegimeEngine` | premarket 時強制 `confidenceLevel=LOW` + regime=`UNKNOWN` | 即使資料齊全，試撮時段 confidence 為 LOW |
| Candidate scoring | premarket 禁用：`priceBreakHigh` / `volumeSpike` / `belowOpen` / `falseBreakout` | 這些旗標必須來自 Phase 2 之後的真實資料 |
| `PremarketWorkflowService` | 輸出改名：從 `finalDecision` → `premarket_bias_report` | 只含題材+候選清單，不含 scoring |
| `OpenDataPrepJob` (09:01) | 擴充：確認 Phase 1→2 切換、refresh regime + scoring | 明確標示「資料從試撮轉真實成交」 |
| `FinalDecision0930Job` (09:30) | 首次允許 `FinalDecisionEngine.evaluate` 輸出 ENTER/REST | 9:30 是唯一 entry 決策觸發點 |

### 5.5 新增 response decision type

```java
public record FinalDecisionResponse(
    String decision,         // ENTER / REST / WAIT（新）
    List<FinalDecisionSelectedStockResponse> selectedStocks,
    List<String> rejectedReasons,
    String summary
)
```

`WAIT` 的語義：
- 不是 REST（REST 是「今日不做」）
- 不是 ENTER
- 是「現在還不是下單時機，等 09:30 之後再評估」

---

## 6. v2.7 其他待決策（非 Session-Aware 相關）

### 6.1 ConsensusScoringEngine 重構（P0）

**現況問題**：把 java 結構分納入共識懲罰。

**建議**：
- `consensus_score` 只算 `claude` 與 `codex` 的共識（AI 層共識）
- `java_structure_score` 退回純權重角色（只在 `WeightedScoringEngine` 出現）
- 這樣**不會把強股（AI 雙高但 java 中等）打死**

**影響面**：改 ConsensusScoringEngine.compute + 相關 tests  
**工時**：2-3 hr

### 6.2 Penalty 係數歸零／降至 0.2（P0）

**現況問題**：`NOT_IN_FINAL_PLAN` / `RR_BELOW_MIN` / `ENTRY_TOO_EXTENDED` 常態觸發，合計扣 3 分。

**建議**（5 分鐘 config 改動）：
```
penalty.not_in_final_plan:       0.5  → 0.1
penalty.rr_below_min:            1.5  → 0.5（或 0）
penalty.entry_too_extended:      1.0  → 0（移到 timing 層）
```

**影響面**：live DB PUT；或 V22 migration  
**工時**：5 分鐘

### 6.3 強替換邏輯（P1）

**現況問題**：`portfolio.allow_new_when_full_strong=true` 但 PortfolioRiskEngine 無對應程式。

**建議**：
- 新 config `portfolio.replace_strong_score_gap=1.5`
- 滿倉時若新候選 finalRank > (最弱持倉 reviewRank + gap) → 觸發替換
- 執行：先平最弱持倉、再進新候選（一個 transaction）

**影響面**：PortfolioRiskEngine + PositionService + tests  
**工時**：3-4 hr

### 6.4 Regime 下游消費（P1）

**現況問題**：`MarketRegimeDecision` 建立但 FinalDecisionEngine 不讀。

**建議**：
- FinalDecisionEngine.evaluate 接收 `MarketRegimeDecision` 參數
- BULL_TREND 時 `grade_b_min -= 0.3`（放寬）
- PANIC_VOLATILITY 時 `grade_ap_min += 0.3`（拉嚴）
- LOW confidence 時 pick count × 0.5（降倉不降標準）

**影響面**：FinalDecisionEngine + FinalDecisionService 介接 + tests  
**工時**：3 hr

### 6.5 Decision Lock 自動清除（P1）

**現況問題**：一旦 `decision_lock=LOCKED` 整天甚至整週不解。

**建議**：
- 每日 08:00 `DailyHealthCheckJob` 檢查：若昨日 lock 原因已失效（如：連虧計數 reset），自動清除
- 保留 manual override（Codex 可主動 lock）

**影響面**：DailyHealthCheckJob + TradingStateService  
**工時**：2 hr

### 6.6 RR 動態計算（P2）

**現況問題**：candidate 建立時的 RR=1.33 是靜態用收盤價算，沒反映開盤跳空後的真實 entry price。

**建議**：
- Phase 2 OPEN_VALIDATION 時重算 RR（用開盤 + 5min 均價作 entry）
- `candidate.rr_live` 欄位 vs `candidate.rr_static`

**影響面**：CandidateScanService + Candidate entity  
**工時**：3 hr

### 6.7 動態加碼機制（P2）

**現況問題**：進場後只有 trailing stop，無加碼、無分批減。

**建議**：
- 新 `PositionAddEngine`：浮盈 > 5% + 結構未破 + 市場 A → 追半倉
- 新 `PositionPartialExitEngine`：TP1 達 → 賣 1/3、TP2 達 → 再賣 1/3、剩 1/3 用 trailing
- Austin CLAUDE.md 本來就這樣規劃，但程式沒實作

**影響面**：PositionDecisionEngine + PositionService + tests  
**工時**：6-8 hr

---

## 7. 建議實作順序（Claude 初擬，Codex 裁決）

### Phase A（本週，阻塞實盤使用）
1. **Session-Aware**（P0） — §5 整節，**阻擋試撮污染，否則其他改動都徒勞** → 8 hr
2. **ConsensusScoringEngine 重構**（§6.1） → 3 hr
3. **Penalty 係數下修**（§6.2） → 5 min

### Phase B（下週，補結構漏洞）
4. **Regime 下游消費**（§6.4） → 3 hr
5. **Decision Lock 自動清除**（§6.5） → 2 hr
6. **強替換邏輯**（§6.3） → 4 hr

### Phase C（2 週後，長期優化）
7. **RR 動態計算**（§6.6） → 3 hr
8. **動態加碼 / 分批減**（§6.7） → 8 hr

---

## 8. 給 Codex 的問題清單

請 Codex 針對以下逐一裁決：

1. **§2.2 ① Consensus 結構**：是否同意拆掉 java 進共識？還是保留但降權重係數？
2. **§3 Top 10**：哪些真的要動、哪些留著不動（例如 `entryTriggered` 是真 bug 還是設計意圖）
3. **§5 Session-Aware**：三階段切點是否合理？09:00 / 09:30 時點是否就是 WAIT→ENTRY 分界？
4. **§5.4 modifications**：`WAIT` decision type 是否要新增？還是用現有 REST 但加 reason=`PREMARKET_NO_DECISION`？
5. **§6.2 Penalty 係數**：降到多低？還是分階段（例如 Phase A 先降一半、Phase B 再降）
6. **§7 順序**：同意 Session-Aware 為 Phase A #1 嗎？
7. **跳過不做的項目**：哪些分析 Claude 太激進 / 不符合 Austin 實際操盤習慣？

---

## 9. 實盤數據支持

### 今日 4/22 實例（最強烈證據）

| 標的 | 真實 AI 評分 | 系統算出 final | 系統 bucket | 是否該買？（主力操盤手角度） |
|------|------------|--------------|-----------|----------------------------|
| 2454 聯發科 | claude=9.0 codex=8.0 | 3.49 → 扣 penalty ≈ 1.5 | **C（REST）** | ✅ 該買（龍頭站前高） |
| 6770 力積電 | claude=7.5 codex=7.0 | 6.48 → 扣 penalty ≈ 4.5 | **C** | ✅ 該買（IC 族群續強） |
| 8039 台虹 | claude=7.0 codex=6.0 | 5.59 → 扣 penalty ≈ 3.6 | **C** | ⚠️ 鎖漲停不可追 |
| 8028 昇陽 | claude=7.0 codex=6.0 | 5.95 → 扣 penalty ≈ 4.0 | **C** | ⚠️ 鎖漲停 |

**事實**：主力會買的 2454 / 6770，系統打到 C → REST。

---

## 10. 重要備註

- 本文件是 **Claude 的主觀觀察 + 建議**，**不是 Austin 最終決策**
- Codex 需審閱 + 挑選要實作的項目
- 工時估算含測試 + 回歸驗證，實際可能 ±30%
- Phase A 之前**不建議實盤放大規模**（先跑小部位驗證 Session-Aware 後行為）

---

> **簽名**：Claude（研究員角色）  
> **對口**：Codex（決策者角色）
> **產出時間**：2026-04-22 13:00
