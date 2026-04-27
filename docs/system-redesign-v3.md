# Trading System v3 重構設計（工程實作規格）

> 狀態：**設計階段，待 Austin 核准 Phase 1 才動工**
> 對齊：本文件以 `Trading System v2`（已實作）為基線，只設計**真正缺失**的模組與**精簡 over-engineered 部分**，避免重造輪子。

---

## 0. 設計原則（最重要）

1. **不重造已有元件**：BacktestEngine / VetoEngine / FinalDecisionEngine / ThemeGateTraceEngine 已存在，本次只「增補」與「精簡」。
2. **Flag-driven roll-out**：所有新模組預設 `enabled=false`，shadow mode 至少 5 個交易日比對 legacy → 才允許 live。
3. **Schema-first**：先定 entity & API，再寫 engine，最後接 UI。
4. **每個 Phase 結尾必須能回答**：「這個改動有沒有讓系統賺更多錢？」用 paper trading + benchmark 驗證。

---

## 1. 現況診斷（具體痛點）

| 痛點 | 證據 | 嚴重度 |
|---|---|---|
| 沒有 forward paper trading | 只有歷史 BacktestRun，無「今日下單虛擬倉」追蹤 | **High** |
| theme_heat_score 是黑盒一個分數 | `ThemeSnapshotEntity.themeHeatScore` 由 Claude 一次給定，無分項 | **High** |
| Claude 缺市場心理欄位 | `StockEvaluationEntity` 只有 claude_score / claude_thesis / risk_flags | **Mid** |
| Sharpe / Sortino 未算 | `BacktestMetricsEngine` 只算 win_rate / drawdown | **Mid** |
| 策略對比無 dashboard | setup vs momentum 各自跑、無 KPI 時序 | **Mid** |
| Theme Engine v2 大多 flag off | `theme.live_decision.enabled=false`, `theme.shadow_mode.enabled=true` 但無人看 report | **Low（過度工程）** |
| 過度保守實際可量化嗎？ | **目前無證據**證明「過度保守」，需先用 paper trading + backtest 給數字 | **要驗證再說** |

> ⚠ **關鍵立場**：Austin 在 prompt 提到「過度嚴謹」「過度保守」是**假設**。在 Paper Trading 跑 4 週、有實際 win_rate / coverage 數字之前，不應降門檻。Phase 1 先建驗證機制，**不動策略參數**。

---

## 2. Theme Heat Engine v2.5（拆解現有 theme_strength）

### 2.1 目標
把現在 `themeHeatScore` 這個「Claude 一次給」的單分數，拆成**可解釋、可由資料驅動**的多個分項。

### 2.2 Schema 變更

**新增 entity：`ThemeHeatBreakdownEntity`**（一對一掛在 ThemeSnapshot 旁，不破壞舊欄位）

```java
@Entity @Table(name = "theme_heat_breakdown")
class ThemeHeatBreakdownEntity {
    Long id;
    LocalDate tradingDate;
    String themeTag;
    // 各分項皆 0-10
    Double themeHeatScore;            // 既有 themeHeatScore（保留為總分）
    Double marketAttentionScore;      // 由族群成交量佔大盤比 + 漲幅集中度
    Double newsDensityScore;          // 24h 新聞數 / 30 日均值
    Double socialVolumeScore;         // 預留欄位（v3.1 才接 PTT/Mobile01 爬蟲）
    Double capitalFlowStrength;       // 三大法人買超 / 族群總市值
    Integer limitUpCount;             // 族群當日漲停家數
    Integer strongStockCount;         // ≥5% 漲幅家數
    String dataSourcesJson;           // 各分項用了哪些資料 + timestamp
    LocalDateTime createdAt;
}
```

**舊 `ThemeSnapshotEntity.themeHeatScore` 保留**：作為 v2.5 的 fallback，等 v3 跑穩後再棄用。

### 2.3 計算規則（純 Java，無 Claude 依賴）

| 欄位 | 公式 | 資料來源 |
|---|---|---|
| `marketAttentionScore` | `min(10, theme_turnover / market_turnover × 100 × 1.5)` | `MarketSnapshotService` + 個股成交額 |
| `newsDensityScore` | `min(10, log2(today_news / 30d_avg) × 5 + 5)` | 新聞 adapter（沿用既有 `NewsClient`，若無則先返 null） |
| `capitalFlowStrength` | `min(10, max(0, theme_t86_net_buy / theme_market_cap × 1000))` | T86 三大法人 |
| `limitUpCount` | 族群當日 `change_pct ≥ 9.9%` 計數 | `CandidateStockEntity` |
| `strongStockCount` | 族群當日 `change_pct ≥ 5%` 計數 | 同上 |
| `themeHeatScore` (v2.5 重算) | `0.30 × marketAttention + 0.25 × capitalFlow + 0.20 × newsDensity + 0.15 × min(10, limitUpCount × 2.5) + 0.10 × min(10, strongStockCount × 1.0)` | 上面組合 |

> **若 newsDensityScore 取不到資料**：權重重分配到其他項，標記 `dataSourcesJson.news=missing`。

### 2.4 Engine & Service

```text
ThemeHeatBreakdownEngine (engine 層, pure)
  ├─ in: ThemeContext + List<CandidateStock> + MarketSnapshot + T86Data
  └─ out: ThemeHeatBreakdownEntity (尚未存)

ThemeHeatBreakdownService (service 層)
  ├─ scheduledRecompute(LocalDate)  // 由 PostmarketDataPrepJob 觸發
  ├─ getLatest(LocalDate, themeTag)
  └─ persistFromEngineOutput(...)
```

### 2.5 整合進 FinalDecisionEngine

**第一階段：shadow only**
- `ThemeStrengthEngine` 維持原計算，但同時產出 v2.5 breakdown，trace 落 DB。
- `ThemeShadowModeService` 比對 v2.5 vs v2 的 finalThemeScore 差異，diff > 1.0 寫 daily report。

**第二階段（flag on）**：
- 新 flag `theme.heat.v25.enabled=false`（預設）。
- 開啟後，`finalThemeScore` 改用 v2.5 breakdown 算出的 `themeHeatScore`，其餘 pipeline 不變。

### 2.6 API

```http
GET /api/themes/heat-breakdown?date=YYYY-MM-DD
GET /api/themes/heat-breakdown/{themeTag}/history?days=30
```

---

## 3. Expectation & Narrative Engine（Claude 升級）

### 3.1 目標
讓 Claude 的研究輸出帶有**市場心理 / 預期差**訊號，而不只是「基本面 OK / 技術面 OK」。

### 3.2 Claude Contract 擴充

修改 `D:\ai\stock\claude-research-contract.md`，新增三個必填欄位：

```json
{
  "expected_narrative": "string，30-80字。市場現在在交易這檔的什麼故事？",
  "surprise_factor": {
    "score": 0,           // -10 ~ +10。負=市場已過度樂觀，正=可能 surprise
    "direction": "POS|NEG|NONE",
    "trigger": "FY2026 法說、Q1 EPS、客戶導入時程..."
  },
  "crowd_psychology": "EARLY|MID|LATE|UNKNOWN",
  // 既有欄位保留
  "claude_score": 0,
  "claude_thesis": "...",
  "claude_risk_flags": []
}
```

### 3.3 Schema 變更

**`StockEvaluationEntity` 新增欄位**（向後相容，allow null）：

```java
String expectedNarrative;          // varchar(500)
Double surpriseScore;              // -10 ~ +10
String surpriseDirection;          // enum: POS/NEG/NONE
String surpriseTrigger;            // varchar(200)
String crowdPsychology;            // enum: EARLY/MID/LATE/UNKNOWN
LocalDateTime narrativeUpdatedAt;
```

### 3.4 Expectation Score 計算

```text
expectation_score (0-10) =
    base 5.0
  + surprise_score × 0.3      // 預期差貢獻
  + crowd_bonus                // EARLY +1.5, MID 0, LATE -2.0
  + narrative_quality_bonus   // 由 Claude 自評信心 0-1，乘 1.5
clamp(0, 10)
```

`expectation_score` 與既有 `final_rank_score` 並存，**不直接覆寫**，而是作為 FinalDecisionEngine 額外加成（見 §6）。

### 3.5 Service & Engine

```text
ExpectationParserService (parser 層)
  └─ 解析 Claude submit JSON 的三個新欄位

ExpectationScoringEngine (engine 層, pure)
  ├─ in: StockEvaluation (with new fields)
  └─ out: expectation_score + reasons

NarrativeAttributionEngine (engine 層, pure)
  └─ 事後分析：實際漲幅 vs surprise_score 是否相關（feed paper trading review）
```

### 3.6 Veto 補強

新增 1 條 soft penalty（`VetoEngine`）：

| Code | 條件 | Penalty |
|---|---|---|
| `LATE_STAGE_CROWDED` | `crowd_psychology == LATE` AND `final_rank_score < 9.0` | -0.8 |

> 不擋 A+，因為 A+ 已經反映市場高度共識，LATE 不一定壞事；只壓 A/B。

---

## 4. Paper Trading System（最關鍵的新模組）

### 4.1 為什麼不用 BacktestRun？
- BacktestRun = 歷史回放，輸入 `start_date / end_date` 重跑。
- PaperTrade = forward live：今天 09:30 FinalDecisionEngine 出 ENTER → 立刻記錄虛擬下單 → 後續每日由排程更新 mark-to-market → 滿足出場條件平倉。**這是驗證「現行策略今天賺不賺錢」的唯一手段**。

### 4.2 Schema

```java
@Entity @Table(name = "paper_trade")
class PaperTradeEntity {
    Long id;
    String tradeId;                  // UUID
    LocalDate entryDate;
    LocalTime entryTime;
    String symbol;
    BigDecimal entryPrice;           // 用 09:30 ~ 09:40 VWAP 或 final decision 當下價
    Integer positionShares;
    BigDecimal positionAmount;       // entryPrice × shares
    BigDecimal stopLossPrice;
    BigDecimal target1Price;         // +8% (or engine-suggested)
    BigDecimal target2Price;
    String source;                   // CODEX / CLAUDE / HYBRID（來自 finalDecision.aiStatus 推導）
    String strategyType;             // SETUP / MOMENTUM_CHASE
    String themeTag;
    Long finalDecisionId;            // FK → final_decision
    Long aiTaskId;                   // FK → ai_task
    Double finalRankScore;           // snapshot at entry
    Double themeHeatScore;           // snapshot at entry
    Double expectationScore;         // snapshot at entry
    // ── 出場欄位 ──
    LocalDate exitDate;              // null = open
    LocalTime exitTime;
    BigDecimal exitPrice;
    String exitReason;               // STOP_LOSS / TARGET_1 / TARGET_2 / TIME_EXIT_5D / MANUAL
    BigDecimal pnlAmount;
    Double pnlPct;
    Integer holdingDays;
    Double mfePct;                   // max favorable excursion
    Double maePct;                   // max adverse excursion
    String status;                   // OPEN / CLOSED / VOID
    String payloadJson;              // 進出場時的決策快照
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
```

### 4.3 出場規則（v1，故意簡單）

| 觸發 | 條件 | exit_reason |
|---|---|---|
| 停損 | mark < stop_loss_price | `STOP_LOSS` |
| T1 停利 | mark ≥ entry × 1.08 | `TARGET_1` |
| T2 停利 | mark ≥ entry × 1.15 | `TARGET_2` |
| 時間出場 | holding_days ≥ 5 且未觸發停損停利 | `TIME_EXIT_5D` |

> v2 可改用 PositionManagementEngine 的 trailing stop / 5MA 規則，**但 v1 先用固定規則**以利和 backtest 對比。

### 4.4 Service 與排程

```text
PaperTradeService
  ├─ openOnFinalDecision(FinalDecisionEntity decision)
  │     └─ 呼叫時機：FinalDecisionService.persist 完成後（同 tx 之外的 event）
  ├─ markToMarketAll(LocalDate)        // 每個交易日 13:35 跑一次
  ├─ closeIfTriggered(...)             // 從 markToMarket 內呼叫
  └─ forceCloseAtTimeLimit(...)        // 5 日強平

PaperTradeJob (scheduler/PaperTradeMtmJob)
  ├─ cron: 0 35 13 * * MON-FRI
  └─ 對所有 OPEN 的 paper_trade 抓收盤價、計算 mfe/mae、判定是否觸發出場

PaperTradeOpenListener (event listener)
  └─ @TransactionalEventListener(after FinalDecisionPersisted)
     └─ 對 ENTER decision 的每個 selected symbol 開 paper_trade
```

### 4.5 KPI 計算

```text
PaperTradeKpiEngine (engine 層, pure)
  ├─ in: List<PaperTradeEntity>（filter by date / strategy / theme）
  └─ out: PaperTradeKpiSnapshot {
        total, win, loss, breakEven,
        winRate, avgReturnPct, medianReturnPct,
        avgWin, avgLoss, profitFactor,    // = sum(win) / |sum(loss)|
        maxDrawdownPct,                   // 累積權益最大回撤
        sharpeRatio,                       // sqrt(252) × mean(daily) / std(daily)
        sortinoRatio,                      // sqrt(252) × mean(daily) / downside_std
        avgHoldingDays,
        bestThemes: List<{theme, count, avgReturn}>,
        bySource: { CODEX: {...}, CLAUDE: {...}, HYBRID: {...} },
        byStrategy: { SETUP: {...}, MOMENTUM_CHASE: {...} }
      }
```

### 4.6 API

```http
POST /api/paper-trades/recalculate?date=YYYY-MM-DD     # 手動補單日 mark-to-market
GET  /api/paper-trades/open
GET  /api/paper-trades/closed?from=&to=&strategy=
GET  /api/paper-trades/kpi?from=&to=
GET  /api/paper-trades/kpi/by-strategy
GET  /api/paper-trades/kpi/by-theme
```

### 4.7 UI（Dashboard 新分頁 `/papers`）

- 上方 KPI 卡：win_rate、avg_return、profit_factor、max_drawdown、sharpe
- 中段表格：open positions（即時 P&L）
- 下方圖表：cumulative equity curve、setup vs momentum 分流
- Filter：source / strategy / theme / date range

---

## 5. Backtest Engine 補強

### 5.1 缺什麼補什麼，不重造

| 已有 | 補強 |
|---|---|
| `BacktestRunEntity` / `BacktestTradeEntity` | ✅ 不動 |
| `BacktestMetricsEngine` 算 win_rate / drawdown | ➕ 加 sharpe / sortino / profit_factor |
| BacktestController 跑 ad-hoc | ➕ 新增 `/run-vs-paper` 端點：用同一份決策日，比對 backtest 出場規則 vs paper trade 實際結果 |
| 無 strategy attribution report | ➕ `BacktestAttributionEngine`：拆解 setup / momentum / theme 對總報酬貢獻 |

### 5.2 一個重要約束

**Backtest 與 Paper Trade 必須用同一個 Engine 出場邏輯**，否則兩邊比對沒意義。

實作：
```java
interface ExitRuleEvaluator {
    ExitDecision evaluate(EntrySnapshot entry, DailyBar bar);
}

class FixedRuleExitEvaluator implements ExitRuleEvaluator { ... }   // v1
class TrailingStopExitEvaluator implements ExitRuleEvaluator { ... } // v2
```

兩邊都注入同一個 evaluator。

---

## 6. FinalDecisionEngine 升級（保守進場）

### 6.1 不降門檻原則
**Phase 1 不動 grade_ap_min / grade_a_min / grade_b_min**。先用 paper trading 4 週數據證明「目前 7.4 門檻覆蓋率太低」，才允許調。

### 6.2 額外加成（可關）

新增 flag `scoring.expectation_bonus.enabled=false`：

```text
adjusted_final_score =
    final_rank_score                       // 既有
  + 0.6 × (expectation_score - 5)          // 期望差中位數 5 為基準
  + 0.4 × (theme_heat_breakdown.themeHeatScore_v25 - 5)
clamp(0, 10)
```

**重點：只能加分，不能扣分到讓 B 升 A**——加成上限 +0.5。實作：
```java
double bonus = clamp(rawBonus, 0.0, 0.5);
double adjusted = baseScore + bonus;
```

### 6.3 Momentum 解鎖路徑

| 階段 | flag | 行為 |
|---|---|---|
| 現在 | `momentum.observation_mode=true` | 候選列出但不下單 |
| Phase 2 | + `momentum.paper_trade.enabled=true` | 開 paper_trade，不發 LINE |
| Phase 3 | + `momentum.live.enabled=true` | 真下單，觀察 4 週 paper trade > 50 trades 才開 |

---

## 7. 系統架構圖（文字）

```text
┌───────────────────────────────────────────────────────────────┐
│                      External Adapters                         │
│   TWSE / TPEx / TAIFEX / T86 / NewsClient(opt) / AI bridge    │
└───────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌───────────────────────────────────────────────────────────────┐
│  Data Prep Layer (existing)                                    │
│   PremarketDataPrepJob / PostmarketDataPrepJob / T86Job       │
└───────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌───────────────────────────────────────────────────────────────┐
│  Theme Heat Engine v2.5  (NEW - §2)                           │
│   ThemeHeatBreakdownEngine → theme_heat_breakdown table       │
│   shadow vs legacy themeHeatScore                              │
└───────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌───────────────────────────────────────────────────────────────┐
│  AI Pipeline (existing + extended)                             │
│   Claude submits {expected_narrative, surprise_factor,         │
│                   crowd_psychology, ...}     ← Contract v3     │
│   Codex reviews → codex_scores                                 │
│   ExpectationParserService (NEW - §3)                         │
└───────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌───────────────────────────────────────────────────────────────┐
│  Scoring Layer (existing + extended)                           │
│   WeightedScoring → ConsensusScoring → ExpectationScoring(NEW)│
└───────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌───────────────────────────────────────────────────────────────┐
│  FinalDecisionEngine (existing, +bonus from §6)                │
│   ENTER / WAIT / WATCH / REST / BLOCKED                        │
└───────────────────────────────────────────────────────────────┘
                            │
            ┌───────────────┴───────────────┐
            ▼                               ▼
   ┌──────────────────┐          ┌──────────────────────┐
   │ Live Notification│          │ Paper Trade (NEW §4) │
   │ (Telegram/LINE)  │          │ open → daily MTM →   │
   │ existing         │          │ exit → KPI snapshot  │
   └──────────────────┘          └──────────────────────┘
                                             │
                                             ▼
                                   ┌──────────────────────┐
                                   │ KPI Dashboard (NEW)  │
                                   │ /papers UI + API     │
                                   └──────────────────────┘
                                             │
                                             ▼
                                   ┌──────────────────────┐
                                   │ Backtest engine      │
                                   │ existing + sharpe/   │
                                   │ attribution (NEW §5) │
                                   └──────────────────────┘
```

---

## 8. 分階段實作（Phase Plan）

### Phase 1 — Paper Trading MVP（**3 個工作天**）

**目標**：今天的 ENTER 決策 → 自動開虛擬倉 → 每日 MTM → 5 日內出場 → KPI 算得出來。

**交付清單**：
- [ ] `PaperTradeEntity` + Flyway migration `V20__paper_trade.sql`
- [ ] `PaperTradeRepository` + 基本查詢
- [ ] `FixedRuleExitEvaluator`（共用 interface）
- [ ] `PaperTradeService.openOnFinalDecision`（event listener，attached to FinalDecisionPersisted event）
- [ ] `PaperTradeMtmJob`（13:35 cron，沿用 `MarketSnapshotService` 取收盤）
- [ ] `PaperTradeKpiEngine`（先實作 win_rate / avg_return / max_drawdown / sharpe）
- [ ] API：`/api/paper-trades/{open,closed,kpi}`
- [ ] Dashboard `/papers` 分頁（最低限度：KPI 卡 + open table + closed table）
- [ ] flag `paper_trade.enabled=true` 預設開（純記錄不影響交易）
- [ ] **驗收**：在沒有 ENTER 的日子，job 跑 0 件無錯；有 ENTER 的日子，paper_trade 紀錄齊全。

### Phase 2 — Theme Heat v2.5 + Expectation Engine（**5 個工作天**）

**目標**：題材熱度可解釋；Claude 多輸出三個欄位；shadow mode 看 4 週差異。

**交付清單**：
- [ ] `ThemeHeatBreakdownEntity` + Flyway `V21`
- [ ] `ThemeHeatBreakdownEngine`（純 Java，不依賴新聞 → null gracefully）
- [ ] 修 `claude-research-contract.md` v3，跑 1 輪 Claude 確認 schema 符合
- [ ] `StockEvaluationEntity` 加 5 個欄位 + Flyway `V22`
- [ ] `ExpectationParserService` + `ExpectationScoringEngine`
- [ ] `VetoEngine` 加 `LATE_STAGE_CROWDED` soft penalty
- [ ] flag `theme.heat.v25.enabled=false`、`scoring.expectation_bonus.enabled=false`
- [ ] Shadow report：每天比對 v25 vs legacy themeHeatScore 差異 → 寫到 `daily_diff_report` 或 LINE 摘要
- [ ] **驗收**：跑 5 個交易日，daily_diff_report 顯示分項合理，無 NaN。

### Phase 3 — Backtest 補強 + 策略對比 + Momentum paper trade（**5 個工作天**）

**目標**：Backtest 算 sharpe；setup vs momentum 並排比較；momentum 進入 paper trade（仍不下真單）。

**交付清單**：
- [ ] `BacktestMetricsEngine` 加 sharpe / sortino / profit_factor
- [ ] `BacktestAttributionEngine`（依 setup / momentum / theme 拆貢獻）
- [ ] `/api/backtest/run-vs-paper`：同期 backtest vs paper trade 對比
- [ ] flag `momentum.paper_trade.enabled=true`：momentum 候選也開 paper_trade（status 標 strategy_type=MOMENTUM_CHASE）
- [ ] Dashboard `/strategy-compare` 分頁：setup vs momentum 4 週滾動 KPI
- [ ] **驗收**：4 週後能回答「目前 setup 策略覆蓋率 X%、win_rate Y%；momentum 在 paper 表現 Z%」。

---

## 9. 風險與限制

| 風險 | 緩解 |
|---|---|
| 模型過擬合（用 paper trading 結果回頭調 setup 門檻） | 強制 backtest holdout：每次參數調整必須在「最近 30 天 paper + 過去 6 個月 backtest」雙樣本表現都改善才上 |
| 資料延遲（T86 18:00 才有） | Paper trade entry 用 09:30~09:40 VWAP；exit 用 next-day open；不依賴 T86 |
| AI 評分不穩定（Claude 來回 +1.5 分） | `score_dispersion` 已存在；新增監控：當週 avg dispersion > 1.5 → 自動關 expectation_bonus 一週 |
| 極端行情（崩盤 / 噴出） | `MarketGateEngine` C 級已 hard veto；補一條：**單日大盤 ±3%**自動把 `paper_trade.exit_evaluator` 切到 trailing stop emergency mode |
| Momentum 暴衝個股 | momentum.paper_trade 必須觀察 ≥ 50 trades 且 max_drawdown < 8% 才允許 live |
| Claude 拒絕填新欄位 | Contract 加 fallback：若三個新欄位為 null，`expectation_score` 取 5.0（中性），不阻塞 pipeline |

---

## 10. 不做的事（明確排除）

- ❌ 不做 social_volume 爬蟲（PTT/Mobile01）— 法律與穩定性風險高，先預留欄位
- ❌ 不重寫 ThemeGateTraceEngine — 8 gate 已可用，只在 G3 之後插 v2.5 breakdown
- ❌ 不動 LINE / Telegram 通知格式 — paper trade 預設不發通知（避免 noise）
- ❌ 不在 Phase 1~3 改 grade_ap_min / grade_a_min — 等 paper trade 證據再說
- ❌ 不建 strategy_recommendation 自動調參 — 屬於 Phase 4+ 範疇

---

## 11. 待 Austin 確認的決策點

1. **Phase 1 起跑日**：是否核准 3 個工作天投入 paper trading？
2. **paper_trade 是否需要 LINE 通知**？預設「不發」（避免和 live decision 重複）。
3. **Claude contract v3 變動**：你要直接改 `claude-research-contract.md`，還是先做 shadow（兩個 contract 並存）？
4. **flag 預設值**：`paper_trade.enabled` 我建議直接 `=true`（因為純記錄不影響交易）。其餘新 flag 全部預設 `=false`。
5. **Momentum 解鎖時間表**：Phase 3 進 paper trade，Phase 4（未排）才考慮 live？

---

> 文件版本：v3.0-draft  /  作者：Claude  /  狀態：待 Austin 核准 Phase 1
