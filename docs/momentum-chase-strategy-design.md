# Momentum Chase 策略路徑設計（v2.3）

> 目標：在現有 Setup 策略之外，新增 **Momentum Chase（動能追價）** 第二條並行策略，吃到「不回測一路噴」的主升段（聯電、瀚宇博這類案例），不破壞 Setup 策略，不靠放寬舊規則。

---

## 一、Strategy 分層（總覽）

```
┌──────────────────────── 候選股 (candidate_stock) ────────────────────────┐
│          掃描：技術面結構分、題材、量能、日高/開盤…                      │
└──────────────────────────────────┬─────────────────────────────────────┘
                                    │
                     Claude 研究 + Codex 審核（不分策略）
                                    │
           ┌────────────────────────┴────────────────────────┐
           ▼                                                 ▼
   SETUP 評分引擎                                 MOMENTUM 評分引擎
   (已存在)                                       (新建)
   - Java structure                              - priceMomentum (0-3)
   - Claude / Codex 加權                         - volume (0-2)
   - VetoEngine[SETUP] 嚴格                      - theme (0-2)
   - final_rank_score (0-10)                     - aiSupport (0-2)
   - 進場門檻 8.8 (A+)                           - structure (0-1)
                                                  - momentum_score (0-10)
                                                  - VetoEngine[MOMENTUM] 寬鬆
                                                  - 進場門檻 7.5
           │                                                 │
           └────────────────────────┬────────────────────────┘
                                    │
                      FinalDecisionService.evaluateAndPersist()
                                    │
                            ┌───────┴───────┐
                            ▼               ▼
                         ENTER           REST/WATCH
                  (selected_stocks
                   標註 strategyType)
                                    │
                                    ▼
                       Position (strategy_type)
                  ┌──────────┴──────────┐
                  ▼                     ▼
            Setup 管理              Momentum 管理
            (正常倉位               (0.5 倉位
             正常停損                更緊停損
             正常 trailing)          更快 exit)
```

### 核心設計原則

| 原則 | 落實 |
|---|---|
| 不破壞 Setup 策略 | VetoEngine 對 SETUP 規則不動；SETUP pipeline 邏輯零修改 |
| Momentum 為並行策略而非 fallback | FinalDecision **同時**跑兩條 pipeline，不是「Setup 空單才跑 Momentum」 |
| 風控更嚴 | 倉位 0.5 倍、停損 -2%~-3%、trailing 更窄、持有 ≤3 日 |
| 可接受較差 RR | momentum_score 計算內**不納入** RR，但 position sizing 會降 |
| Veto 分層 | 流程性欄位（NO_THEME / NOT_IN_FINAL_PLAN / THEME_LOW）對 MOMENTUM 降為 **scoring penalty**，不 hard veto；但 SCORE_DIVERGENCE_HIGH 與 AI 強烈負評仍保留 veto |

---

## 二、Momentum 判斷邏輯（v1 規格）

### 資料來源需求

| 資料 | 來源 | 狀態 |
|---|---|---|
| 今日價量、開盤、日高/日低 | `candidate_stock.payload_json`（現有） | ✅ 現成 |
| 5MA / 10MA | **需新建** `TwseHistoricalClient` 或 `price_history` 表 | ⚠ 需補 |
| 連續上漲 N 日 | 同上 | ⚠ 需補 |
| 20 日新高 | 同上 | ⚠ 需補 |
| 5 日均量 | 同上 | ⚠ 需補 |
| themeRank / finalThemeScore | `theme_snapshot`（現有） | ✅ 現成 |
| claudeRiskFlags / claudeScore | `stock_evaluation`（現有） | ✅ 現成 |
| codexVetoSymbols | `ai_task.codex_veto_symbols_json`（現有） | ✅ 現成 |

**v1 實作決策**：先用**輕量版 fallback**，不阻塞上線：
- 沒 OHLC 歷史時，用「今日收盤 vs 今日開盤」與「近 N 日 candidate scan 中出現過的最高 dayHigh」近似；
- v1.1 再補 `TwseHistoricalClient` + `price_history` 表。

### MomentumCandidateEngine 的基本篩選

候選股在掃描階段可能被貼 `is_momentum_candidate = TRUE`，只要**至少 3 項**為真：

1. **價格動能**
   - 今日收盤 > 昨收 × 1.03（日漲幅 ≥ 3%）
   - 或 今日價 ≥ 近 20 日新高（有歷史時）
   - 或 連續 2~3 日 bar 收盤上漲且今日未跌破開盤

2. **均線結構**（有歷史時才計分，無歷史時此項不算）
   - 價格 > 5MA
   - 5MA > 10MA
   - 5MA 上彎（今日 5MA > 昨日 5MA）

3. **成交量**
   - 今日量 > 5 日均量 × 1.5
   - 或 盤中突破昨高時量能明顯放大（volume_spike=TRUE）

4. **題材**
   - `themeRank ≤ 2`
   - 或 `finalThemeScore ≥ 7`

5. **AI 評價**
   - Claude `riskFlags` 不含重大風險（見下方「強烈負評」定義）
   - Codex 未把本檔列入 vetoSymbols

### 強烈負評定義

任一成立即視為 **AI 強烈負評**（會觸發 Momentum veto）：

- `codex_veto_symbols_json` 含本 symbol
- `claude_score < 4.0`
- `claude_risk_flags` 含任一：`LIQUIDITY_TRAP / EARNINGS_MISS / INSIDER_SELLING / VOLUME_SPIKE_LONG_BLACK / SUSPENDED_WARN`

---

## 三、Momentum Scoring（獨立評分，不用 A+）

### 計算公式

```
momentum_score =
      priceMomentumScore   (0 ~ 3)
    + volumeScore          (0 ~ 2)
    + themeScore           (0 ~ 2)
    + aiSupportScore       (0 ~ 2)
    + structureScore       (0 ~ 1)
    - vetoPenalty          (0 ~ 2, 見第四節)
```

滿分 10；進場門檻 `momentum_score >= 7.5`。

### 各項分數規則

| 子分數 | 範圍 | 規則 |
|---|---|---|
| priceMomentumScore | 0-3 | 日漲 ≥5%：3；≥3%：2；≥1.5%：1；否則 0。連續 2 日上漲再 +0.5（上限 3） |
| volumeScore | 0-2 | 今日量 ≥ 5MA × 2：2；≥ 5MA × 1.5：1.5；≥ 5MA：1；否則 0。爆量長黑扣 1 |
| themeScore | 0-2 | themeRank=1：2；themeRank=2：1.5；finalThemeScore ≥ 7：1；≥ 5：0.5；否則 0 |
| aiSupportScore | 0-2 | claude_score ≥ 7：1。codex_score ≥ 7：1。兩者疊加 |
| structureScore | 0-1 | 站上 5MA：0.5；5MA 上彎：+0.5。無歷史時此項給 0.5（中性） |

### 進場決策閾值

| momentum_score | 行為 |
|---|---|
| < 5.0 | 不進場 |
| 5.0 ~ 7.5 | WATCH（標記為 Momentum 觀察，但不下單） |
| ≥ 7.5 | 可 ENTER（Momentum） |
| ≥ 9.0 | 強 Momentum（倉位可回升到 0.7 倍，但仍 < Setup） |

---

## 四、Veto 規則調整（關鍵）

### 現有 VetoEngine（SETUP 用）不動

14 條規則全部保留：
- MARKET_GRADE_C, DECISION_LOCKED, TIME_DECAY_LATE
- RR_BELOW_MIN, NOT_IN_FINAL_PLAN, NO_STOP_LOSS
- HIGH_VAL_WEAK_MARKET
- NO_THEME, CODEX_SCORE_LOW, THEME_NOT_IN_TOP, THEME_SCORE_TOO_LOW
- SCORE_DIVERGENCE_HIGH, VOLUME_SPIKE_NO_BREAKOUT, ENTRY_TOO_EXTENDED

### VetoEngine 新簽名（重構）

```java
public VetoResult evaluate(VetoInput input, StrategyType strategy);
```

舊呼叫點（FinalDecisionService 的 SETUP 管線）一律傳 `StrategyType.SETUP`，行為不變。
新增的 MOMENTUM 管線傳 `StrategyType.MOMENTUM_CHASE`，執行以下**分層表**：

| 規則 | SETUP 行為 | MOMENTUM 行為 | 備註 |
|---|---|---|---|
| MARKET_GRADE_C | HARD_VETO | HARD_VETO | 大盤 C 級誰都不做 |
| DECISION_LOCKED | HARD_VETO | HARD_VETO | 系統鎖定 |
| TIME_DECAY_LATE | HARD_VETO | PENALTY(-0.5) | 尾盤 Momentum 接受 |
| RR_BELOW_MIN | HARD_VETO | **跳過檢查** | Momentum 本質就是較差 RR |
| NOT_IN_FINAL_PLAN | HARD_VETO | PENALTY(-0.5) | 流程性欄位不封殺 |
| NO_STOP_LOSS | HARD_VETO | **強制補停損 -2%** | Momentum 自帶停損邏輯 |
| HIGH_VAL_WEAK_MARKET | HARD_VETO | PENALTY(-1.0) | Momentum 可接受高估值但扣分 |
| **NO_THEME** | HARD_VETO | PENALTY(-1.0) | 流程性欄位 |
| CODEX_SCORE_LOW | HARD_VETO | PENALTY(-0.5) | 降為扣分 |
| **THEME_NOT_IN_TOP** | HARD_VETO | PENALTY(-0.5) | |
| **THEME_SCORE_TOO_LOW** | HARD_VETO | PENALTY(-0.5) | |
| SCORE_DIVERGENCE_HIGH | HARD_VETO | **HARD_VETO** | 保留：Java/Claude 差 ≥2.5 代表有爭議 |
| VOLUME_SPIKE_NO_BREAKOUT | HARD_VETO | HARD_VETO | 爆量無突破是典型出貨訊號，Momentum 最怕 |
| ENTRY_TOO_EXTENDED | HARD_VETO | PENALTY(-1.0) | 乖離大扣分但可進（momentum 本質 extended） |
| **AI_STRONG_NEGATIVE**（新）| — | HARD_VETO | 新增：Claude 明確負評或 Codex veto，禁止追價 |

### VetoResult 擴充

```java
public record VetoResult(
    boolean vetoed,
    List<String> reasons,
    BigDecimal scoringPenalty    // 給 Momentum 用：累積的 penalty
) {}
```

SETUP 呼叫端忽略 `scoringPenalty`，MOMENTUM 呼叫端把它從 momentum_score 裡扣掉。

---

## 五、FinalDecision Flow

### 新流程（取代現有 `evaluateAndPersist`）

```
1. resolveAiReadiness(tradingDate, preferTaskType)
   - 若 AI_NOT_READY / PARTIAL_AI_READY → REST（與現狀一致，不分策略）

2. 載入候選股 candidates = CandidateScanService.loadFinalDecisionCandidates(...)

3. 並行跑兩條 pipeline：

   Setup Pipeline (現有)
   ───────────────────
   setupScored = applyScoringPipeline(candidates, SETUP)
   setupPicks  = FinalDecisionEngine.evaluate(setupScored)
                 // 條件：isVetoed=false AND final_rank_score >= 8.8

   Momentum Pipeline (新)
   ─────────────────────
   momentumCandidates = filter(candidates, isMomentumCandidate)
                         // 至少 3/5 基本篩選條件
   momentumScored = applyMomentumScoringPipeline(momentumCandidates)
                    // 含 VetoEngine[MOMENTUM]
   momentumPicks  = filter(momentumScored, m -> m.momentumScore >= 7.5
                                            && !m.hardVetoed)

4. 合併（去重）
   - 若同一 symbol 同時在 setupPicks 與 momentumPicks：
       選 Setup（較穩）、標 strategyType=SETUP，同時記「亦符合 MOMENTUM」
   - 合併總量 ≤ portfolio.max_open_positions（預設 3）
   - Setup 優先填，Momentum 填剩下名額

5. 產出 FinalDecisionResponse：
   - decision = ENTER / WATCH / REST
   - selected_stocks 每檔帶 strategyType: "SETUP" | "MOMENTUM_CHASE"
   - summary 內標註「本次含 N 檔 Momentum 追價」
   - rejected_reasons 保留原因（含 "momentum_score=6.8 below 7.5"）

6. persistAndReturn:
   - FinalDecisionEntity.strategy_type = 主要策略
     （全 SETUP→SETUP；全 MOMENTUM→MOMENTUM；混合→MIXED）
   - finalize 對應 AI task
```

### 決策優先級表

| Setup 有 A+ | Momentum ≥ 7.5 | 最終行為 |
|---|---|---|
| ✓ | ✗ | ENTER (SETUP) |
| ✗ | ✓ | ENTER (MOMENTUM_CHASE) |
| ✓ | ✓ | ENTER (mixed)：Setup 先、Momentum 候補 |
| ✗ | ✗ | REST（同今日行為） |

### 位階與停損差異（Position Sizing）

| 屬性 | SETUP | MOMENTUM_CHASE |
|---|---|---|
| base position | 100%（單檔 30K~50K） | **50%**（單檔 15K~25K） |
| 強 Momentum (score≥9) 可加至 | — | 70% |
| 停損 | -4% ~ -6% | **-2% ~ -3%**（硬停損） |
| trailing | 5MA 或前低 | **5MA 破即出**（更緊） |
| 目標價 TP1 | +6% ~ +8% | +5% ~ +7% |
| 目標價 TP2 | +10% ~ +15% | +8% ~ +12% |
| 持有上限 | 10 交易日 | **3 交易日**（追不到就認錯） |
| 停利移動停損 | 觸發 TP1 後 break-even | 觸發 +3% 後立即 break-even |

---

## 六、Position 管理（Momentum 專屬出場邏輯）

### `PositionDecisionEngine` 分支

```
if (position.strategy_type == MOMENTUM_CHASE):
    # Momentum 出場 (任一為真即全出)
    - 跌破進場價 × 0.98 → STOP_LOSS
    - 跌破 5MA → TRAILING_STOP
    - bar 爆量長黑（單根 >1.5% 且量 > 5MA×2）→ MOMENTUM_COLLAPSE
    - 持有超過 3 交易日且未破 TP1 → TIME_STOP
    - 大盤由 B 降 C → EMERGENCY_EXIT（對 Momentum 特別敏感）
else:
    # 原 Setup 出場邏輯（不變）
```

### 出場原因新增

`exit_reason` 新增：
- `MOMENTUM_COLLAPSE`（爆量長黑）
- `TIME_STOP`（3 日未達 TP1）
- `EMERGENCY_EXIT`（大盤降級，Momentum 專屬）

---

## 七、Watchlist 調整

現有 `watchlist_stock` 有 `observation_days` 累積天數門檻（需觀察 N 日才能 READY）。

Momentum 候選 bypass：

```
if (candidate.is_momentum_candidate && momentum_score >= 7.0):
    watchlist.status = READY  # 當日即可
    watchlist.observation_days = 0（不要求）
    watchlist.strategy_type = MOMENTUM_CHASE
```

---

## 八、DB / Schema 變更

### 新 migration：`V10__momentum_chase_strategy.sql`

```sql
-- 1. candidate_stock：標記是否為 Momentum 候選
ALTER TABLE candidate_stock
    ADD COLUMN is_momentum_candidate BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN momentum_flags_json JSON NULL COMMENT 'which of 5 basic conditions met';

-- 2. stock_evaluation：momentum_score + strategy_type
ALTER TABLE stock_evaluation
    ADD COLUMN momentum_score DECIMAL(4,2) NULL,
    ADD COLUMN momentum_sub_scores_json JSON NULL,
    ADD COLUMN strategy_type VARCHAR(20) NULL COMMENT 'SETUP | MOMENTUM_CHASE';

-- 3. final_decision：此筆決策屬於哪種策略
ALTER TABLE final_decision
    ADD COLUMN strategy_type VARCHAR(20) NULL COMMENT 'SETUP | MOMENTUM_CHASE | MIXED';

-- 4. position：所屬策略影響出場邏輯
ALTER TABLE position
    ADD COLUMN strategy_type VARCHAR(20) NOT NULL DEFAULT 'SETUP';

-- 5. watchlist_stock：記錄 momentum 類別
ALTER TABLE watchlist_stock
    ADD COLUMN strategy_type VARCHAR(20) NOT NULL DEFAULT 'SETUP';

-- 6. score_config：Momentum 門檻與 penalty 權重（可熱更新）
INSERT IGNORE INTO score_config (config_key, config_value, value_type, description) VALUES
('momentum.enabled',                'true', 'BOOLEAN', 'Momentum Chase 策略是否啟用'),
('momentum.entry_score_min',        '7.5',  'DECIMAL', 'Momentum 進場最低 score'),
('momentum.watch_score_min',        '5.0',  'DECIMAL', 'Momentum 觀察最低 score'),
('momentum.strong_score_min',       '9.0',  'DECIMAL', '強 Momentum 門檻（可加碼至 70%）'),
('momentum.position_size_ratio',    '0.5',  'DECIMAL', 'Momentum 相對 Setup 的倉位比例'),
('momentum.strong_position_ratio',  '0.7',  'DECIMAL', '強 Momentum 倉位比例'),
('momentum.stop_loss_pct',          '-0.025','DECIMAL', 'Momentum 預設停損 -2.5%'),
('momentum.max_holding_days',       '3',    'INTEGER', 'Momentum 最長持有日'),
('momentum.veto_penalty.no_theme',       '1.0', 'DECIMAL', ''),
('momentum.veto_penalty.not_in_plan',    '0.5', 'DECIMAL', ''),
('momentum.veto_penalty.theme_low',      '0.5', 'DECIMAL', ''),
('momentum.veto_penalty.extended',       '1.0', 'DECIMAL', ''),
('momentum.veto_penalty.codex_low',      '0.5', 'DECIMAL', ''),
('momentum.veto_penalty.high_val',       '1.0', 'DECIMAL', '');

-- 7. 可選（v1.1 才補）：price_history 支援 5MA/10MA/20 日新高
-- CREATE TABLE price_history (...);
```

### Entity 變更對照

| Entity | 新增 fields |
|---|---|
| `CandidateStockEntity` | `isMomentumCandidate`, `momentumFlagsJson` |
| `StockEvaluationEntity` | `momentumScore`, `momentumSubScoresJson`, `strategyType` |
| `FinalDecisionEntity` | `strategyType` |
| `PositionEntity` | `strategyType`（default=SETUP，舊資料不動） |
| `WatchlistStockEntity` | `strategyType` |

### DTO / Response

- `FinalDecisionSelectedStockResponse` 加 `strategyType` 欄位
- `FinalDecisionResponse.summary` 在有 Momentum 時附註「本次含 N 檔追價」
- `PositionResponse` 加 `strategyType`

---

## 九、LINE 通知規範

### 最終決策訊息範例

**純 Setup（維持現狀）：**
```
【09:30 今日操作】2026-04-21
📊 行情：B 級（震盪偏多）
🎯 決策：ENTER
✅ 進場標的：
  [SETUP] 2330 台積電 | 30,000 元 | 停損 -5%
來源：Trading System
```

**含 Momentum：**
```
【09:30 今日操作（含追價單）】2026-04-21
📊 行情：A 級（主升段）
🎯 決策：ENTER
✅ 進場標的：
  [SETUP]    2330 台積電 | 30,000 元 | 停損 -5% | TP +8%
  [Momentum] 2303 聯電   | 15,000 元 | 停損 -2.5% | TP +6% | 追高警示
⚠️ Momentum 追價單風險提示：倉位已壓低、停損已收緊、持有上限 3 日
來源：Trading System
```

**純 Momentum：**
```
【09:30 今日操作（追價單）】2026-04-21
...
```

### Position Alert

```
[Momentum 持倉警報] 2303 聯電
狀態：跌破 5MA
建議：立即出場（MOMENTUM_COLLAPSE）
```

### 盤後檢討訊息

在 14:00 REVIEW 與 15:30 POSTMARKET 通知加入 Momentum 分區：

```
📈 今日 Setup 戰況：2/3 勝
🔥 今日 Momentum 戰況：1/1 勝（聯電 +5.3%）
```

---

## 十、實作順序（建議分 4 個 PR）

### PR1 — Schema + Enum + Entity（低風險）
1. `V10__momentum_chase_strategy.sql`
2. 新 enum `StrategyType { SETUP, MOMENTUM_CHASE }`
3. Entity 欄位擴充（`PositionEntity.strategyType` default SETUP 確保舊資料不動）
4. DTO 加 `strategyType`（向下相容 ctor）
5. **驗收**：現有測試全過、server 起得來、舊功能行為不變

### PR2 — Momentum 評分引擎（Java 層判斷，不含 AI）
6. `MomentumScoringEngine`（子分數 + 總分）
7. `MomentumCandidateEngine`（5 條基本篩選，無歷史資料時用 fallback）
8. `CandidateScanService` 掃描時標記 `is_momentum_candidate`
9. `ScoreConfigService` 載入 `momentum.*` keys
10. 單元測試：聯電類 fixture（漲 5%、主流題材）→ momentum_score ≥ 7.5

### PR3 — VetoEngine 分層 + FinalDecision 平行 pipeline（核心）
11. `VetoEngine.evaluate(input, strategyType)` 重構，加 `scoringPenalty`
12. SETUP 呼叫端全部傳 `StrategyType.SETUP`（行為不變）
13. `FinalDecisionService.evaluateAndPersist` 新增 momentum pipeline
14. `FinalDecisionResponse` / `SelectedStock` 帶 `strategyType`
15. LINE 訊息加 `[SETUP]` / `[Momentum]` 前綴
16. 單元測試：
    - 聯電 fixture → 產生 MOMENTUM ENTRY
    - 傳統 Setup fixture → 產生 SETUP ENTRY（不受影響）
    - 兩者並存時 Setup 優先
    - `NO_THEME` 對 MOMENTUM 只扣分不 veto

### PR4 — Position / Watchlist 差異化（出場與觀察）
17. `PositionEntity.strategyType` 參與 `PositionDecisionEngine`
18. Momentum 出場規則（MOMENTUM_COLLAPSE / TIME_STOP / EMERGENCY_EXIT）
19. Position create 時若 strategyType=MOMENTUM → 倉位 0.5 倍、停損 -2.5%
20. Watchlist bypass observationDays for Momentum
21. Dashboard UI：持倉/候選/決策列表顯示 strategy badge
22. 單元測試：Momentum 持倉 3 日未達 TP1 → TIME_STOP

### PR5（可選，v1.1）— 歷史資料強化
23. `TwseHistoricalClient` 或 `price_history` 表
24. 5MA / 10MA / 20 日高的精確計算
25. MomentumScoringEngine 升級到完整版

---

## 十一、風險與邊界

### 設計層面

| 風險 | 緩解 |
|---|---|
| Momentum 濫發訊號 → 過度交易 | `momentum.entry_score_min=7.5` 門檻偏嚴、每日最多 1 檔 Momentum、倉位 0.5 倍 |
| Setup 與 Momentum 同檔雙倉 | 合併階段以 symbol 去重，單檔僅一種策略 |
| Momentum 停損太緊被洗出 | v1 用 -2.5% 觀察實績，後續可依統計放寬到 -3%；且同檔因 Momentum 被洗後進入 cooldown 避免重複追價 |
| SCORE_DIVERGENCE_HIGH 仍 veto 掉 Momentum | 設計上這是特意保留 — Java/Claude 差太大代表有爭議，追價風險放大，寧可錯過 |
| `is_momentum_candidate` 被錯誤標記拖累 Setup | 標記只影響 Momentum pipeline，Setup pipeline **完全不讀這欄位** |

### 實作層面

- **向下相容**：`PositionEntity.strategyType DEFAULT 'SETUP'` 確保既有持倉（00631L）繼續走 Setup 管理
- **熱開關**：`momentum.enabled=false` 時 Momentum pipeline 全略過，等同回到純 Setup
- **觀察期**：上線後 2 週內 momentum_score ≥ 9.0 才允許實際 ENTER，其餘只 WATCH + LINE 提醒（用 config `momentum.observation_mode=true` 控）

---

## 十二、驗收條件（設計階段先列出，PR 時實作對應測試）

| # | 情境 | 預期 |
|---|---|---|
| 1 | 聯電類：日漲 5%、主流題材 theme_rank=1、無負評、5MA 上彎 | `is_momentum_candidate=true`、`momentum_score ≥ 7.5`、產出 MOMENTUM ENTER |
| 2 | 傳統 Setup：回測、A+ final_rank ≥ 8.8、RR ≥ 2.5 | 產出 SETUP ENTER（行為與現在相同） |
| 3 | NO_THEME + 其他皆優的 Momentum 候選 | SETUP 被 veto，**MOMENTUM 通過**（扣 1.0 分，剩 ≥ 7.5） |
| 4 | SCORE_DIVERGENCE_HIGH 的 Momentum 候選 | SETUP 與 MOMENTUM 都被 veto（保留嚴格性） |
| 5 | Codex 把本檔列入 veto_symbols | MOMENTUM 也被 AI_STRONG_NEGATIVE veto |
| 6 | Momentum 持倉 3 日未達 TP1 | TIME_STOP 出場 |
| 7 | Momentum 持倉 bar 爆量長黑 | MOMENTUM_COLLAPSE 出場 |
| 8 | 同一 symbol 同時 A+ 且 Momentum ≥ 7.5 | 標 SETUP，不建立兩筆 position |
| 9 | `momentum.enabled=false` | Momentum pipeline 完全略過，系統退回 v2.2 行為 |
| 10 | Dashboard / LINE 顯示 strategy badge | SETUP / Momentum 前綴 + 倉位/停損差異清楚可辨 |

---

## 十三、不在此設計範圍（明確排除）

- 權證、選擇權、期貨 Momentum：v1 只處理現股
- 盤中即時 Momentum 進場（當沖追價）：Level 4 規則已禁止，這裡只處理「當日策略決定、隔日開盤或盤中執行」
- AI 自學調整 momentum 權重：後續版本再做
- 多 lens 並行（除了 Setup + Momentum 外的第三種策略）：未來議題

---

> 若對以上設計無異議，即可按「實作順序」PR1 → PR5 依序開發。
> 每個 PR 完成都保證：既有 Setup 路徑零回歸、momentum 功能可熱開關關閉。
