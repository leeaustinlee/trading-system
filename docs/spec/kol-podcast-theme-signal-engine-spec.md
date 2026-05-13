# KOL / Podcast Theme Signal Engine — Architecture Spec

狀態：Design / MVP Proposal  
撰寫時間：2026-05-13  
範圍：AI 股票交易系統 `/mnt/d/ai/stock/trading-system`  
原則：只設計，不直接改 production decision path；KOL / Podcast signal 永遠是 weak signal。

---

## 0. Executive Summary

本規格建議新增一套 **KOL / Podcast Theme Signal Engine**，定位為：

> 外部題材雷達 / 市場關注度來源，用於題材發現、情緒觀察、主流輪動與候選觀察；不是交易訊號，不可直接 BUY，不可 override 風控或 `FinalDecisionEngine`。

第一階段 MVP：

```text
手動輸入 KOL / Podcast / YouTube / 新聞摘要文字
  → AI 結構化成題材訊號
  → 寫入可追蹤 DB
  → 聚合成每日 theme-level weak signal
  → Theme Engine / Candidate Engine 可讀取
  → Claude / Codex prompt 可引用
  → Shadow mode 比較有 signal vs 無 signal
  → Backtest / evaluation 後才考慮 live soft boost
```

最重要的設計取捨：

1. **不要直接接 `FinalDecisionEngine`**：外部觀點太 noisy，接 final path 會造成跟單與追高風險。
2. **不要把 KOL 分數塞進 `theme_heat_score`**：現有欄位語意是 Claude heat，混用會污染 audit 與 backtest。
3. **先獨立 raw / mapping / evidence / trace tables**：保留來源、AI 萃取、證據與 replay 能力。
4. **先進 Theme / Prompt / Shadow，不進 live scoring**。
5. 若未來開 live，只能 capped soft boost：`+0.2 ~ +0.5`，且所有 hard gate 優先。

---

## 1. 現況盤點

### 1.1 Theme Engine v2

相關檔案：

- `src/main/java/com/austin/trading/entity/ThemeSnapshotEntity.java`
- `src/main/java/com/austin/trading/entity/StockThemeMappingEntity.java`
- `src/main/java/com/austin/trading/engine/ThemeSelectionEngine.java`
- `src/main/java/com/austin/trading/engine/ThemeStrengthEngine.java`
- `src/main/java/com/austin/trading/service/ThemeService.java`
- `src/main/java/com/austin/trading/service/ThemeGateOrchestrator.java`
- `src/main/java/com/austin/trading/service/ThemeShadowModeService.java`
- `sql/V23__theme_engine_v2_foundation.sql`

現有資料模型：

- `theme_snapshot`
  - 每交易日每題材一筆。
  - 主要欄位：
    - `market_behavior_score`
    - `theme_heat_score`
    - `theme_continuation_score`
    - `driver_type`
    - `risk_summary`
    - `final_theme_score`
    - `ranking_order`
    - `payload_json`
- `stock_theme_mapping`
  - 個股與題材 mapping。
  - 支援 `source=MANUAL/AUTO/CODEX/CLAUDE`。
- `ThemeSelectionEngine`
  - 依題材成員漲幅、強勢股比例、一致性計算 `market_behavior_score`。
  - 用 market behavior + Claude heat + continuation 算 `final_theme_score`。
  - `getThemeMultiplier(symbol, date)` 會把 `final_theme_score` 轉成 0.8~1.2 multiplier。
- `ThemeStrengthEngine`
  - 使用 `ThemeStrengthInput` 算 `strengthScore`、stage、decay risk、tradable。
  - 現有公式大致為：

```text
strengthScore = marketBehavior * w_mb
              + claudeHeat * w_heat
              + claudeContinuation * w_cont
              + breadth * w_breadth
```

適合接入點：

- **最佳**：`theme_snapshot.payload_json` 放 KOL aggregate 引用摘要；正式分數仍由獨立 table 管。
- **後續**：`ThemeStrengthInput` 可新增 external weak fields，但需 feature flag + shadow。
- **不建議**：第一期直接改 `ThemeSelectionEngine.computeFinalThemeScore()` production formula。

### 1.2 Candidate Engine / MomentumCandidateEngine

相關檔案：

- `CandidateScanService.java`
- `CandidateStockEntity.java`
- `MomentumCandidateEngine.java`
- `CandidateBatchItemRequest.java`

現況：

- `candidate_stock` 存候選股，含 `score`、`theme_tag`、`payload_json`、`is_momentum_candidate`、`momentum_flags_json`。
- `CandidateScanService.saveBatchWithGate()` 可啟用：
  - `candidate.momentum_gate.enabled`
  - `candidate.changepct_hard_gate.enabled`
- `MomentumCandidateEngine` 五條條件：
  1. priceMomentum
  2. MA structure
  3. volume
  4. theme：`themeRank <= 2` 或 `finalThemeScore >= 7`
  5. aiSupport

適合接入點：

- MVP：只在 candidate payload / response 加 `kolContext`，不改 gate。
- Shadow：計算 `kolBoostShadow`、`wouldUpgradeBucket`、`riskConflict`。
- Live 之後：最多作 sorting soft boost，不新增第六個 hard condition。

### 1.3 FinalDecisionEngine

相關檔案：

- `FinalDecisionEngine.java`
- `FinalDecisionService.java`
- `FinalDecisionCandidateRequest.java`

現況：

- 有硬性 market gate：`market_grade=C` 休息。
- 有 `decision_lock=LOCKED` gate。
- 有 session-aware gate。
- 有 `PriceGateEvaluator`。
- 有 `ChasedHighEntryEngine` shadow/live gate。
- 有 `tradabilityTag` hard block / soft penalty。
- 分桶：A+ / A / B / C，依 `finalRankScore` 與 RR。

設計結論：

- **不要把 KOL signal 直接接到 FinalDecisionEngine 作為 ENTER 條件**。
- 若未來要接，只能在所有 hard reject 之後、分桶前做 capped rank trace，而且 default off。
- KOL signal 不可使：
  - market C → ENTER
  - LOCKED → ENTER
  - vetoed → revive
  - priceGate BLOCK → pass
  - chased-high BLOCK → pass
  - tradabilityTag 不列主進場 → pass

### 1.4 Claude Deep Research Flow

相關檔案：

- `ClaudeCodeRequestWriterService.java`
- `AiTaskEntity.java`
- `ClaudeSubmitBridgeService.java`
- `ClaudeSubmitWatcherJob.java`
- `ClaudeThemeResearchParserService.java`

現況：

- Java 將 request 寫成 JSON：`claude-research-request.json`。
- request 包含：`taskId`、`taskType`、`trading_date`、`allowed_symbols`、`market_context`、rules files、capital context、open positions。
- Claude 結果經 submit/validator/bridge 回寫。

適合接入點：

- 在 request JSON 增加 `external_theme_signals` / `kol_theme_context`。
- Prompt 必須明確寫 guardrail：
  - KOL signal weak only。
  - 不得因 KOL 提及直接 BUY。
  - risk gate / price / volume / capital 優先。
  - mentioned symbols 是 reference，不是推薦。

### 1.5 Codex Review Flow

相關檔案：

- `AiTaskEntity.java`
- `CodexResultPayloadRequest.java`
- `AiCodexClient.java`
- `FinalDecisionService.java`

現況：

- Codex 作為 review / final payload 的重要來源。
- `codex_payload_json`、`codex_scores_json`、`codex_veto_symbols_json` 可保存結果。

適合接入點：

- Codex prompt 增加 skeptical review：
  - theme confirmation check
  - crowding risk
  - FOMO risk
  - late-stage detection
  - source recycling / echo chamber
- Codex 可 veto KOL-driven FOMO，但 KOL 不可 override Codex veto。

### 1.6 Database Schema

目前 migration 到 `V31__position_daily_review.sql`。Theme v2 shadow foundation 在 `V23__theme_engine_v2_foundation.sql`：

- `theme_shadow_decision_log`
- `theme_shadow_daily_report`

設計結論：

- 新功能應新增 `V32__kol_theme_signal_engine.sql`，不可修改既有 migration。
- KOL 原文 / evidence / trace 應獨立保存，不應把全文塞進 `theme_snapshot.payload_json`。

### 1.7 Scheduler / Jobs

相關檔案：

- `scheduler/*Job.java`
- `SchedulerExecutionLogEntity.java`
- `SchedulerLogService.java`

現況：

- 已有盤前、開盤、盤中、盤後、T86、health、AI sweep、Claude watcher 等 job。

MVP：

- 不需要自動 crawler job。
- 只需要 manual API + optional daily aggregation job。
- 後續可新增：
  - `KolSignalDailyAggregationJob`
  - `KolSignalShadowReportJob`
  - `KolSignalExpiryJob`

### 1.8 Telegram / Line Notification

相關檔案：

- `NotificationFacade.java`
- `TelegramTemplateService.java`
- `LineTemplateService.java`
- `TradingNotificationDecisionFormatter.java`

設計結論：

- MVP 不主動推播所有 KOL raw signal，避免噪音。
- 可做每日 shadow summary：標示 `[SHADOW][KOL]`。
- 交易通知中若引用 KOL signal，必須顯示：`弱訊號 / 非買賣依據 / 已通過或未通過風控`。

### 1.9 Existing AI Trace / Decision Trace

現況：

- `theme_shadow_decision_log` 有 legacy vs theme score / trace。
- `FinalDecisionEntity`、`ExecutionDecisionLogEntity`、`AiTaskEntity` 都保存 AI / decision payload。

設計結論：

- KOL signal 必須可 trace：來源 → raw content → AI extraction → theme/stock mapping → aggregate → prompt use → shadow score → final decision reference。
- 不可只留一個 aggregate 分數。

---

## 2. Architecture Design

### 2.1 High-level Architecture

```text
[Manual Input]
    |
    v
[KOL Raw Input API]
    |
    v
[kol_theme_signal/raw_content]
    |
    v
[AI Extraction Task]
    |-- Claude/Codex structure prompt
    v
[kol_theme_signal.ai_summary / mentioned themes / stocks]
    |
    +--> [kol_theme_stock_mapping]
    +--> [theme_signal_evidence]
    +--> [kol_signal_trace]
    |
    v
[KOL Signal Aggregation Engine]
    |
    v
[Daily Theme External Signal Context]
    |
    +--> Theme Engine context / payload reference
    +--> Candidate payload soft context
    +--> Claude prompt augmentation
    +--> Codex skeptical review prompt
    +--> Shadow Mode diff / backtest
```

### 2.2 Data Flow Diagram

```text
┌──────────────────────┐
│ 使用者手動貼上內容   │
│ Podcast / KOL / News │
└──────────┬───────────┘
           │ POST /api/kol-signals
           v
┌──────────────────────┐
│ KolSignalController  │
└──────────┬───────────┘
           v
┌──────────────────────┐
│ KolSignalIngestion   │
│ - hash/dedupe        │
│ - store raw          │
└──────────┬───────────┘
           v
┌──────────────────────┐
│ AI Extraction Task   │
│ Claude/Codex manual  │
│ structured JSON      │
└──────────┬───────────┘
           v
┌─────────────────────────────┐
│ KolSignalExtractionService  │
│ - validate schema           │
│ - map theme aliases         │
│ - map symbols               │
│ - write evidence/trace      │
└──────────┬──────────────────┘
           v
┌─────────────────────────────┐
│ KolSignalAggregationEngine  │
│ theme attention/freshness   │
│ crowding/conviction         │
└──────────┬──────────────────┘
           v
┌─────────────────────────────────────────────┐
│ Theme/Candidate/Prompt/Shadow Consumers      │
│ - ThemeSnapshot payload reference            │
│ - Candidate kolContext                       │
│ - Claude external_theme_signals              │
│ - Codex crowding/FOMO review                 │
│ - Shadow/backtest                            │
└─────────────────────────────────────────────┘
```

### 2.3 Event-driven Design

建議事件：

```java
KolSignalCreatedEvent
KolSignalStructuredEvent
KolSignalAggregatedEvent
KolSignalShadowEvaluatedEvent
```

用途：

- ingestion 與 aggregation 解耦。
- 未來自動 crawler、Podcast ASR、YouTube transcript、Telegram monitor 都只需產生同一種 raw input event。
- 可觀測性：每個 event 都可寫 trace。

---

## 3. DB Schema

使用者指定 table：

- `kol_theme_signal`
- `kol_theme_stock_mapping`
- `kol_signal_trace`
- `theme_signal_evidence`

建議 migration：`sql/V32__kol_theme_signal_engine.sql`

### 3.1 `kol_theme_signal`

用途：raw input + AI structured summary 的主表。一筆代表一個來源內容單位，例如一集 podcast、一篇 KOL 貼文、一段新聞摘要。

```sql
CREATE TABLE IF NOT EXISTS kol_theme_signal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,

    trading_date DATE NOT NULL,
    source_name VARCHAR(120) NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    source_url VARCHAR(800) NULL,
    source_author VARCHAR(120) NULL,
    published_at TIMESTAMP NULL,

    content_hash VARCHAR(128) NOT NULL,
    raw_content LONGTEXT NOT NULL,
    ai_summary TEXT NULL,

    mentioned_themes JSON NULL,
    mentioned_stocks JSON NULL,

    sentiment VARCHAR(30) NULL,
    conviction_score DECIMAL(6,3) NULL,
    freshness_score DECIMAL(6,3) NULL,
    evidence_level VARCHAR(30) NULL,
    signal_strength DECIMAL(6,3) NULL,
    market_regime VARCHAR(40) NULL,

    extraction_status VARCHAR(30) NOT NULL DEFAULT 'RAW',
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    payload_json JSON NULL,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_kol_signal_hash (content_hash),
    KEY idx_kol_signal_date_source (trading_date, source_type, source_name),
    KEY idx_kol_signal_published (published_at),
    KEY idx_kol_signal_status (trading_date, status, extraction_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

欄位說明：

- `raw_content`：手動輸入全文或摘要；MVP 不存音檔、不存 ASR chunks。
- `ai_summary`：AI 結構化後的人讀摘要。
- `mentioned_themes` / `mentioned_stocks`：保留 JSON 方便快速呈現；正式 relation 由 mapping tables 管。
- `evidence_level`：`DIRECT_CLAIM / REASONED_ANALYSIS / RUMOR / SECOND_HAND / UNKNOWN`。
- `signal_strength`：單篇內容的外部訊號強度，不等於交易分數。

### 3.2 `kol_theme_stock_mapping`

用途：一筆 KOL source 中，某個 theme 與某檔股票的關係。

```sql
CREATE TABLE IF NOT EXISTS kol_theme_stock_mapping (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    kol_signal_id BIGINT NOT NULL,
    trading_date DATE NOT NULL,

    theme_tag VARCHAR(100) NOT NULL,
    theme_alias VARCHAR(120) NULL,
    symbol VARCHAR(20) NULL,
    stock_name VARCHAR(120) NULL,

    direction VARCHAR(30) NOT NULL,
    relation_type VARCHAR(40) NOT NULL DEFAULT 'MENTIONED',
    confidence_score DECIMAL(6,3) NULL,
    conviction_score DECIMAL(6,3) NULL,
    novelty_score DECIMAL(6,3) NULL,
    crowding_risk_score DECIMAL(6,3) NULL,
    freshness_score DECIMAL(6,3) NULL,

    reason_summary VARCHAR(1000) NULL,
    risk_summary VARCHAR(1000) NULL,
    payload_json JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    KEY idx_kol_mapping_date_theme (trading_date, theme_tag),
    KEY idx_kol_mapping_date_symbol (trading_date, symbol),
    KEY idx_kol_mapping_signal (kol_signal_id),
    KEY idx_kol_mapping_theme_direction (theme_tag, direction),
    CONSTRAINT fk_kol_mapping_signal
      FOREIGN KEY (kol_signal_id) REFERENCES kol_theme_signal(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

`direction` 建議 enum：

- `BULLISH`
- `EARLY_BULLISH`
- `CAUTIOUS`
- `BEARISH`
- `NEUTRAL`
- `MIXED`

`relation_type`：

- `MENTIONED`
- `SUPPLY_CHAIN`
- `LEADER`
- `LAGGARD`
- `RISK_EXAMPLE`
- `AVOID`
- `PEER_REFERENCE`

### 3.3 `theme_signal_evidence`

用途：保留 explainability。每個 theme/stock signal 都要可回到 evidence 片段。

```sql
CREATE TABLE IF NOT EXISTS theme_signal_evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    kol_signal_id BIGINT NOT NULL,
    mapping_id BIGINT NULL,

    evidence_type VARCHAR(40) NOT NULL,
    evidence_text TEXT NOT NULL,
    source_offset_start INT NULL,
    source_offset_end INT NULL,
    timestamp_start_sec INT NULL,
    timestamp_end_sec INT NULL,

    evidence_level VARCHAR(30) NULL,
    confidence_score DECIMAL(6,3) NULL,
    payload_json JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    KEY idx_theme_evidence_signal (kol_signal_id),
    KEY idx_theme_evidence_mapping (mapping_id),
    CONSTRAINT fk_evidence_signal
      FOREIGN KEY (kol_signal_id) REFERENCES kol_theme_signal(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

`evidence_type`：

- `QUOTE`
- `PARAPHRASE`
- `AI_INFERENCE`
- `NUMERIC_CLAIM`
- `RUMOR`

### 3.4 `kol_signal_trace`

用途：追蹤每次 extraction / aggregation / prompt / shadow 使用情況。

```sql
CREATE TABLE IF NOT EXISTS kol_signal_trace (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    kol_signal_id BIGINT NULL,
    trading_date DATE NOT NULL,

    trace_type VARCHAR(50) NOT NULL,
    trace_status VARCHAR(30) NOT NULL,
    actor VARCHAR(50) NOT NULL,

    input_json JSON NULL,
    output_json JSON NULL,
    error_message VARCHAR(1000) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    KEY idx_kol_trace_signal (kol_signal_id),
    KEY idx_kol_trace_date_type (trading_date, trace_type),
    KEY idx_kol_trace_status (trace_status),
    CONSTRAINT fk_trace_signal
      FOREIGN KEY (kol_signal_id) REFERENCES kol_theme_signal(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

`trace_type`：

- `RAW_INGESTED`
- `AI_EXTRACTION_REQUESTED`
- `AI_EXTRACTION_DONE`
- `AGGREGATED_DAILY`
- `THEME_CONTEXT_ATTACHED`
- `CANDIDATE_CONTEXT_ATTACHED`
- `CLAUDE_PROMPT_AUGMENTED`
- `CODEX_PROMPT_AUGMENTED`
- `SHADOW_EVALUATED`
- `BACKTEST_REPLAYED`

### 3.5 Optional aggregate table：`kol_theme_signal_daily_snapshot`

雖然使用者指定四張表已足夠，但為查詢與 replay 建議新增 daily aggregate table。若要嚴格只先做四張表，這張可 Phase 2 再加。

```sql
CREATE TABLE IF NOT EXISTS kol_theme_signal_daily_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trading_date DATE NOT NULL,
    theme_tag VARCHAR(100) NOT NULL,

    theme_attention_score DECIMAL(6,3) NULL,
    theme_confirmation_score DECIMAL(6,3) NULL,
    theme_freshness_score DECIMAL(6,3) NULL,
    theme_rotation_score DECIMAL(6,3) NULL,
    crowding_risk_score DECIMAL(6,3) NULL,
    kol_boost_shadow DECIMAL(6,3) NULL,

    source_count INT NOT NULL DEFAULT 0,
    unique_source_count INT NOT NULL DEFAULT 0,
    mentioned_stock_count INT NOT NULL DEFAULT 0,

    top_sources_json JSON NULL,
    top_stocks_json JSON NULL,
    evidence_json JSON NULL,
    risk_flags_json JSON NULL,
    payload_json JSON NULL,

    generated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_kol_daily_date_theme (trading_date, theme_tag),
    KEY idx_kol_daily_date_attention (trading_date, theme_attention_score),
    KEY idx_kol_daily_date_freshness (trading_date, theme_freshness_score)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 3.6 JSON vs Normalized

建議策略：

- **Normalized**：需要查詢、回測、join 的資料。
  - source、theme、symbol、direction、score、published_at、trading_date。
- **JSON**：彈性 payload、AI schema、evidence list、top sources、risk flags。
- **不可只用 JSON**：否則 backtest / dashboard / index 查詢會痛苦。
- **不可過度 normalized**：AI extraction schema 會變，payload_json 保留演進彈性。

### 3.7 Backtest Replay Capability

每筆 signal 必須保存：

- `published_at`：來源發布時間。
- `created_at`：系統 ingest 時間。
- `trading_date`：系統歸屬交易日。
- `content_hash`：去重與 replay 穩定性。
- `payload_json.extraction_model` / `prompt_version`：AI 結構化版本。
- `kol_signal_trace`：每次 aggregation / prompt / shadow 版本。

---

## 4. Java Class / Package 建議

### 4.1 Package

```text
com.austin.trading.kol
com.austin.trading.kol.controller
com.austin.trading.kol.dto
com.austin.trading.kol.entity
com.austin.trading.kol.repository
com.austin.trading.kol.service
com.austin.trading.kol.engine
com.austin.trading.kol.scheduler
```

若想維持現有 flat package，可用：

```text
com.austin.trading.controller.KolSignalController
com.austin.trading.service.KolSignalIngestionService
...
```

但建議新 package，避免污染既有 trading core。

### 4.2 Entities

- `KolThemeSignalEntity`
- `KolThemeStockMappingEntity`
- `ThemeSignalEvidenceEntity`
- `KolSignalTraceEntity`
- Phase 2 optional：`KolThemeSignalDailySnapshotEntity`

### 4.3 DTOs

Request：

- `KolSignalCreateRequest`
- `KolSignalStructureSubmitRequest`
- `KolThemeSignalItemRequest`
- `KolSignalAggregationRequest`

Response：

- `KolSignalResponse`
- `KolThemeSignalDashboardResponse`
- `KolThemeSignalDailySnapshotResponse`
- `KolSignalTraceResponse`

Internal DTO：

```java
public record ExternalThemeSignalContext(
    String themeTag,
    BigDecimal attentionScore,
    BigDecimal confirmationScore,
    BigDecimal freshnessScore,
    BigDecimal rotationScore,
    BigDecimal crowdingRiskScore,
    BigDecimal cappedBoost,
    int sourceCount,
    List<String> topSources,
    List<String> mentionedSymbols,
    List<String> riskFlags,
    String summary
) {}
```

### 4.4 Services / Engines

- `KolSignalIngestionService`
  - raw create / dedupe / hash。
- `KolSignalExtractionPromptService`
  - 建 AI structuring prompt。
- `KolSignalExtractionService`
  - ingest AI structured JSON、validate、write mapping/evidence/trace。
- `KolSignalAggregationService`
  - rebuild daily aggregate。
- `KolThemeSignalEngine`
  - pure scoring：attention / freshness / rotation / crowding / capped boost。
- `KolSignalContextService`
  - 提供 Theme/Candidate/Prompt consumers 讀取 external context。
- `KolSignalShadowModeService`
  - 計算 shadow score diff。
- `KolSignalBacktestService`
  - replay / metrics。

---

## 5. API Design

Base path：`/api/kol-signals`

### 5.1 手動建立 raw KOL signal

```http
POST /api/kol-signals
```

Request：

```json
{
  "tradingDate": "2026-05-13",
  "sourceName": "股癌 Gooaye",
  "sourceType": "PODCAST",
  "sourceUrl": "https://podcasts.apple.com/...",
  "sourceAuthor": "Gooaye",
  "publishedAt": "2026-05-13T16:00:31+08:00",
  "rawContent": "這集提到被動元件、散熱、電力基建...",
  "marketRegime": "BULL_TREND"
}
```

Response：

```json
{
  "id": 101,
  "contentHash": "sha256:...",
  "extractionStatus": "RAW",
  "status": "ACTIVE"
}
```

### 5.2 建立 AI extraction prompt / task

```http
POST /api/kol-signals/{id}/extract-task
```

Response：

```json
{
  "kolSignalId": 101,
  "aiTaskId": 987,
  "taskType": "KOL_THEME_REVIEW",
  "status": "PENDING"
}
```

### 5.3 回填 AI structured result

```http
POST /api/kol-signals/{id}/structured-result
```

Request：

```json
{
  "aiSummary": "主持人偏正向看被動元件漲價循環，對機器人題材偏謹慎...",
  "globalWarnings": ["Podcast 為滯後來源，不可直接追價"],
  "themes": [
    {
      "themeTag": "被動元件",
      "themeAlias": "MLCC / 鋁電容",
      "direction": "BULLISH",
      "sentiment": "POSITIVE",
      "convictionScore": 7.2,
      "freshnessScore": 6.8,
      "evidenceLevel": "REASONED_ANALYSIS",
      "signalStrength": 6.5,
      "reasonSummary": "高階 AI 料號排擠產能，標準品漲價可能擴散",
      "riskSummary": "需確認是需求驅動而非成本轉嫁",
      "mentionedStocks": [
        {"symbol": "2327", "stockName": "國巨", "relationType": "LEADER"},
        {"symbol": "2492", "stockName": "華新科", "relationType": "PEER_REFERENCE"}
      ],
      "evidence": [
        {
          "evidenceType": "PARAPHRASE",
          "evidenceText": "節目提及 MLCC/電容低階料漲價與高階 AI 料號排擠產能",
          "confidenceScore": 0.78
        }
      ]
    }
  ]
}
```

### 5.4 重建 daily aggregate

```http
POST /api/kol-signals/aggregate?date=2026-05-13
```

### 5.5 Dashboard 查詢

```http
GET /api/kol-signals/dashboard?date=2026-05-13
GET /api/kol-signals/themes?date=2026-05-13
GET /api/kol-signals/themes/{themeTag}?date=2026-05-13
GET /api/kol-signals/stocks/{symbol}?from=2026-05-01&to=2026-05-13
GET /api/kol-signals/{id}/trace
```

### 5.6 Shadow / Backtest

```http
POST /api/kol-signals/shadow/run?date=2026-05-13
GET  /api/kol-signals/shadow/report?date=2026-05-13
POST /api/kol-signals/backtest/replay?from=2026-04-01&to=2026-05-13
GET  /api/kol-signals/backtest/report?from=2026-04-01&to=2026-05-13
```

---

## 6. Signal Extraction Pipeline

### 6.1 Input → Output Contract

輸入文字例：

```text
最近 AI server 散熱應該還有戲，
但機器人很多已經炒太高了，
我反而開始看電力基建。
```

AI structured output：

```json
{
  "themes": [
    {
      "themeTag": "AI Server Cooling",
      "direction": "BULLISH",
      "sentiment": "POSITIVE",
      "convictionScore": 6.8,
      "freshnessScore": 5.5,
      "crowdingRiskScore": 4.0,
      "earlyTheme": false,
      "marketConsensus": "MEDIUM",
      "reasonSummary": "仍看好 AI server 散熱需求延續",
      "guardrail": "不可直接視為買進訊號"
    },
    {
      "themeTag": "Robotics",
      "direction": "CAUTIOUS",
      "sentiment": "NEGATIVE_TO_NEUTRAL",
      "convictionScore": 6.0,
      "freshnessScore": 2.0,
      "crowdingRiskScore": 8.0,
      "earlyTheme": false,
      "marketConsensus": "HIGH",
      "reasonSummary": "題材已大幅反映，追高風險高"
    },
    {
      "themeTag": "Power Infrastructure",
      "direction": "EARLY_BULLISH",
      "sentiment": "POSITIVE",
      "convictionScore": 6.5,
      "freshnessScore": 8.0,
      "crowdingRiskScore": 3.5,
      "earlyTheme": true,
      "marketConsensus": "LOW_TO_MEDIUM",
      "reasonSummary": "資金可能尋找 AI infrastructure 的下一段擴散"
    }
  ]
}
```

### 6.2 Pipeline Steps

```text
1. Normalize raw content
   - trim
   - content hash
   - source metadata
   - published_at / created_at / trading_date 對齊

2. AI extraction
   - themes
   - stocks
   - direction
   - sentiment
   - conviction
   - freshness
   - evidence level
   - reasons
   - market consensus
   - early theme / crowded theme

3. Validation
   - score range 0~10 or 0~1
   - direction enum
   - unknown stock/company name fuzzy match
   - unknown theme alias mapping
   - mentioned stock != recommendation

4. Theme alias resolution
   - AI Server Cooling → AI散熱 / 散熱
   - Power Infrastructure → 電力基建
   - Robotics → 機器人
   - Unknown → staging / manual review

5. Stock mapping
   - symbol explicit > company name lookup > supply chain inference
   - inference 必須標 `relation_type=SUPPLY_CHAIN` 並降低 confidence

6. Evidence extraction
   - quote / paraphrase / AI inference 分離
   - evidence_level 標註

7. Aggregation
   - same source dedupe
   - source count / unique source count
   - freshness decay
   - crowding risk
   - capped boost

8. Consumers
   - Theme context
   - Candidate reference
   - Prompt augmentation
   - Shadow/backtest
```

### 6.3 Prompt for Extraction

```text
你是台股交易系統的外部題材訊號萃取器。
你的任務是把 KOL / Podcast / 新聞摘要轉成可回測、可追蹤的題材訊號。

重要限制：
- 這不是交易建議。
- KOL 提到股票不等於推薦。
- 不得輸出 BUY / SELL / ENTER。
- 若資訊不足，請標 confidence 低或 evidence_level=UNKNOWN。
- 若題材已高度共識或疑似追高，請提高 crowdingRiskScore。
- 若只是謠言或二手轉述，請 evidence_level=RUMOR 或 SECOND_HAND。

請輸出 JSON，schema：...
```

---

## 7. Theme Engine Integration

### 7.1 Theme-level Scores

KOL aggregate 產生四個主分：

```text
theme_attention_score      外部來源關注度
theme_confirmation_score   多來源互相確認程度
theme_freshness_score      題材新鮮度 / 是否 early
theme_rotation_score       是否從舊主流擴散到新主線
crowding_risk_score        過熱 / FOMO / late-stage 風險
```

### 7.2 Formula 建議

MVP shadow only：

```text
kol_raw_boost =
  0.35 * normalized_attention
+ 0.25 * normalized_confirmation
+ 0.25 * normalized_freshness
+ 0.15 * normalized_rotation
- 0.35 * normalized_crowding_risk

kol_boost_shadow = clamp(kol_raw_boost, -0.3, +0.2)
```

Phase 3 after validation：

```text
kol_boost_live = clamp(kol_raw_boost, -0.5, +0.5)
```

### 7.3 Integration Policy

- `theme_snapshot.final_theme_score` 初期不變。
- 在 `theme_snapshot.payload_json.externalThemeSignals` 放摘要引用。
- `ThemeSnapshotResponse` 可增加 external fields 顯示，但需向後相容。
- `ThemeStrengthEngine` Phase 2 可新增 optional input，但 feature flag default false。

---

## 8. Candidate Integration

### 8.1 MVP：reference only

Candidate payload 增加：

```json
{
  "kolContext": {
    "themeTag": "被動元件",
    "mentionedBySources": 2,
    "themeAttentionScore": 7.1,
    "themeFreshnessScore": 6.8,
    "crowdingRiskScore": 4.2,
    "kolBoostShadow": 0.2,
    "riskFlags": ["WEAK_SIGNAL_ONLY"]
  }
}
```

### 8.2 Live 限制

若未來開 live：

- 最高 `+0.2` 起步，最多 `+0.5`。
- 不得使 B 直接變 A+。
- 不得 bypass risk gate。
- 不得改 `isMomentumCandidate` hard condition。
- 不得讓 `MomentumCandidateEngine` 第 5 條變成「KOL 提及即通過」。

建議：

```text
finalRankScoreShadow = finalRankScore + kolBoostShadow
```

只記錄，不用於 live ENTER。

---

## 9. Claude / Codex Prompt Integration

### 9.1 Claude Deep Research Augmentation

`ClaudeCodeRequestWriterService` 增加：

```json
"external_theme_signals": {
  "weak_signal_only": true,
  "contract_note": "External/KOL signals are context only. Never recommend BUY solely because of KOL mention.",
  "themes": [
    {
      "themeTag": "散熱",
      "sourceSummary": "股癌最近關注：散熱、電力基建；對機器人偏謹慎",
      "marketHeatTrend": "RISING",
      "sourceCount": 3,
      "freshnessScore": 8.2,
      "crowdingRiskScore": 4.1,
      "mentionedSymbols": ["3017", "3324"],
      "evidenceLevel": "REASONED_ANALYSIS",
      "guardrail": "不得因 KOL 提及而直接給 BUY"
    }
  ]
}
```

Claude 必答：

- 是否同意該題材歸因？
- 是 early theme、mid trend、late crowded，還是 noise？
- 與量價 / 籌碼 / 法說是否一致？
- 是否造成 FOMO / 追高風險？
- 若 KOL 與 market behavior 衝突，請優先風控。

### 9.2 Codex Final Review Augmentation

Codex prompt 增加：

```text
External Theme Signals Review:
- theme confirmation check
- crowding risk
- FOMO risk
- late-stage theme detection
- source recycling / echo chamber risk

Hard guardrails:
- Do not upgrade to BUY/ENTER solely due to KOL.
- Do not override Java hard gates.
- If KOL is positive but price is extended / near day high / weak volume, flag FOMO risk.
```

Codex output 增加：

```json
"externalSignalReview": {
  "verdict": "CONFIRM | NOISE | OVERHEATED | CONTRADICT | NEEDS_VERIFICATION",
  "riskFlags": ["CROWDED_NARRATIVE", "FOMO_RISK"],
  "notes": "...",
  "vetoSymbols": []
}
```

---

## 10. Scoring Strategy

### 10.1 Score Dimensions

每個 theme signal：

- `sentiment`：positive / negative / mixed。
- `conviction_score`：來源語氣與論證強度。
- `freshness_score`：是否新鮮 / early。
- `evidence_level`：證據品質。
- `signal_strength`：綜合外部訊號強度。
- `crowding_risk_score`：過熱風險。

### 10.2 Time Decay

```text
age_hours = now - published_at
freshness_decay = exp(-age_hours / half_life)
```

建議：

- Podcast half-life：72 小時。
- YouTube / KOL post half-life：48 小時。
- News summary half-life：24 小時。

### 10.3 Source Weight

MVP source weight 先固定：

```text
MANUAL_RESEARCH_SUMMARY: 1.0
PODCAST: 0.7
YOUTUBE: 0.6
NEWS: 0.6
SOCIAL_POST: 0.4
UNKNOWN: 0.2
```

未來加入 KOL credibility score。

### 10.4 Boost Cap

```text
shadow_positive_cap = +0.2
shadow_negative_cap = -0.3
future_live_positive_cap = +0.5
future_live_negative_cap = -0.5
```

絕對禁止：

- KOL-only BUY。
- KOL boost 讓 hard rejected 復活。
- KOL boost 讓 market C 進場。
- KOL boost 把 B 直接升 A+。

---

## 11. Shadow Mode Strategy

Feature flags：

```properties
kol.signal.ingestion.enabled=true
kol.signal.ai_extraction.enabled=true
kol.signal.aggregate.enabled=true
kol.signal.shadow_mode=true
kol.signal.prompt_augmentation.enabled=true
kol.signal.candidate_context.enabled=true
kol.signal.live_score.enabled=false
kol.signal.final_decision.enabled=false
kol.signal.max_positive_boost=0.2
kol.signal.max_negative_penalty=0.3
```

Shadow 比較：

```text
base candidate / base theme
vs
base + KOL weak context / capped boost
```

記錄：

- base score
- shadow score
- score diff
- would upgrade bucket
- would enter if live
- blocked by risk gate
- KOL conflict with price gate
- trace JSON

Diff types：

```text
KOL_NO_EFFECT
KOL_SCORE_ONLY_DIFF
KOL_WOULD_UPGRADE_BUCKET
KOL_WOULD_DOWNGRADE_BUCKET
KOL_WOULD_ENTER_BUT_RISK_BLOCKED
KOL_CONFLICT_WITH_CROWDING_RISK
KOL_CONFLICT_WITH_PRICE_GATE
```

---

## 12. Backtest / Evaluation Strategy

不要做「有提到 → 有漲」這種低品質驗證。必須評估：

### 12.1 Theme Discovery Metrics

- `theme_discovery_lead_time`
  - KOL 首次提及距離 Theme Engine market behavior 爆發的天數。
- `missed_trend_reduction`
  - 原 candidate engine 未納入，但 KOL signal 提早提示的主流題材比例。
- `early_theme_precision`
  - high freshness + low crowding signal 後 T+3/T+5 題材平均是否優於 baseline。

### 12.2 Candidate Quality Metrics

- KOL boosted candidates vs non-boosted candidates：
  - T+1 / T+3 / T+5 return
  - max drawdown
  - win rate
  - reward/risk
  - average holding days
- `would_upgrade_count` 與 upgrade 後表現。
- `blocked_by_risk_then_down_count`：風控擋掉 KOL positive 後是否避免虧損。

### 12.3 FOMO / Chasing Metrics

- KOL positive 且：
  - near day high
  - gap up
  - volume spike long upper shadow
  - 連漲多日
- 後續：
  - open-high-low-close reversal rate
  - next-day drawdown
  - 追高失敗率

### 12.4 Source Metrics

- source precision by `source_name/source_type`。
- source recall。
- source average lead time。
- source false positive cost。
- source crowding penalty hit rate。

### 12.5 Go-live Criteria

至少 20~40 個交易日 shadow：

```text
- boosted group T+3 expectancy > baseline
- max drawdown 不高於 baseline
- FOMO false positive 不增加
- high crowding risk 對應較差 forward return，證明風險分有效
- Codex OVERHEATED verdict 能降低追高失敗
- 所有 risk gate conflict 都正確維持 blocked
```

---

## 13. UI Design

風格：trading terminal style、mobile friendly、dark mode friendly、快速閱讀。

### 13.1 KOL Signal Dashboard

```text
┌─────────────────────────────────────────────┐
│ KOL Theme Signal Dashboard    2026-05-13    │
├─────────────────────────────────────────────┤
│ Shadow Mode: ON   Live Score: OFF           │
│ Sources Today: 5  Themes: 8  Stocks: 23     │
│ Top Risk: CROWDED_NARRATIVE / FOMO_RISK     │
└─────────────────────────────────────────────┘
```

### 13.2 今日熱門題材

```text
#  Theme        Attention Freshness Crowd Risk Sources  Signal
1  被動元件       8.1       7.4       4.2        3        +0.20 shadow
2  電力基建       7.5       8.6       3.1        2        +0.20 shadow
3  機器人         6.2       2.0       8.8        4        -0.20 caution
```

### 13.3 題材輪動

```text
AI Server Cooling  ────────► Power Infrastructure
Robotics           ──X crowded / late-stage
Passive Components ────────► Demand-driven price hike watch
```

### 13.4 KOL Timeline

```text
15:30 股癌 Podcast  被動元件 bullish / 機器人 cautious
16:10 News Summary  電力基建 bullish
18:20 YouTube       散熱 mixed, roadmap delayed not cancelled
```

### 13.5 Stock Mention Heatmap

```text
Symbol  Name    Mentions  Theme       Direction  Risk
2327    國巨       2       被動元件      BULLISH    VERIFY_DEMAND
3017    奇鋐       1       散熱          MIXED      ROADMAP_RISK
xxxx    xxx        3       機器人        CAUTIOUS   CROWDED
```

### 13.6 Theme → Stock Mapping

```text
被動元件
  - 2327 國巨     LEADER          confidence 0.78
  - 2492 華新科   PEER_REFERENCE  confidence 0.70
  - 6173 信昌電   LAGGARD         confidence 0.52
```

### 13.7 Signal Trace

```text
raw input #101
  → AI extraction prompt v1
  → theme mapping: 被動元件 / 電力基建 / 機器人
  → evidence: 3 snippets
  → daily aggregate: attention 8.1, boost +0.2 shadow
  → Claude prompt attached: yes
  → Codex review: OVERHEATED? no
  → final decision impact: none, shadow only
```

---

## 14. Scheduler Design

MVP 不做 crawler。只做 optional jobs：

### 14.1 `KolSignalAggregationJob`

- 時間：每日 08:20 / 15:10 / manual trigger。
- 功能：重建當日 daily aggregate。
- 輸出：`kol_signal_trace(AGGREGATED_DAILY)`。

### 14.2 `KolSignalShadowReportJob`

- 時間：15:40 盤後，或 final decision 後。
- 功能：計算 shadow diff 與 report。
- 通知：Telegram/Line 私訊，標 `[SHADOW][KOL]`。

### 14.3 `KolSignalExpiryJob`

- 時間：每日 08:00。
- 功能：將過期 signal 標 `EXPIRED`。
- 根據 source half-life。

### 14.4 Future ingestion jobs

- YouTube transcript ingestion。
- Podcast RSS ingestion。
- Telegram/Discord monitor。
- PTT/Reddit theme scanner。

全部都只進 raw input，不直接進決策。

---

## 15. Risk Control

硬性規則：

1. KOL signal is weak only。
2. 不得輸出 BUY / ENTER。
3. 不得 override `FinalDecisionEngine`。
4. 不得 bypass：
   - market grade C
   - decision lock
   - veto
   - price gate
   - chased-high gate
   - tradabilityTag hard block
   - capital / position sizing
5. 單一來源最高 boost capped。
6. 高 crowding risk 不加分，必要時扣分。
7. evidence level 低於 `REASONED_ANALYSIS` 不加 live boost。
8. mentioned stock 不等於 candidate。
9. unknown theme 不自動新增到 production mapping；進 staging / manual review。
10. 所有 signal 可關閉、可 trace、可 replay。

AI guardrails：

```text
If KOL signal conflicts with price/volume/risk gate, risk gate wins.
If KOL source is a rumor or second-hand claim, do not increase score.
If theme is late-stage/crowded, mark FOMO risk rather than bullish boost.
```

---

## 16. Rollout Plan

### Phase 0 — Spec only

- 完成本文件。
- 不改 code。
- 確認 integration points。

### Phase 1 — DB + entities + manual ingestion

- `V32__kol_theme_signal_engine.sql`
- entities/repositories。
- `POST /api/kol-signals`。
- 不接 Theme/Candidate/Decision。

### Phase 2 — AI extraction contract

- Extraction prompt。
- Structured result submit API。
- 寫 `kol_theme_stock_mapping` / `theme_signal_evidence` / `kol_signal_trace`。

### Phase 3 — Daily aggregation + dashboard API

- `KolThemeSignalEngine`。
- Daily aggregate table 或 derived query。
- Dashboard endpoints。

### Phase 4 — Prompt augmentation

- `ClaudeCodeRequestWriterService` 加 optional `external_theme_signals`。
- Codex review prompt 加 skeptical section。
- Feature flag default on for prompt context，但 weak only。

### Phase 5 — Shadow scoring

- `kol.signal.shadow_mode=true`。
- 計算 base vs KOL shadow。
- 盤後 shadow report。

### Phase 6 — Backtest / evaluation

- 20~40 交易日。
- forward return / MDD / FOMO / early discovery metrics。

### Phase 7 — Limited live soft boost

條件達標才開：

```properties
kol.signal.live_score.enabled=true
kol.signal.max_positive_boost=0.2
```

所有 hard gate 優先。

### Phase 8 — Expand sources / automation

- Podcast ingestion。
- YouTube transcript。
- Telegram / Discord monitor。
- PTT / Reddit scanner。
- News clustering。

---

## 17. Test Plan

### 17.1 Unit Tests

- `KolThemeSignalEngineTest`
  - cap 正確。
  - freshness decay 正確。
  - crowding risk 高時不加分。
  - low confidence signal ignored。
- `KolSignalExtractionServiceTest`
  - invalid JSON rejected。
  - unknown theme staging。
  - mentioned stock relation type 正確。
- `KolSignalAggregationServiceTest`
  - multiple sources aggregation。
  - duplicate content hash 不重複計分。

### 17.2 Contract Tests

- `KolSignalControllerContractTest`
- `ClaudeCodeRequestWriterKolContextTest`
- `CodexPromptKolGuardrailTest`

必測：

- `weak_signal_only=true`。
- prompt 含 `Never output BUY solely because of KOL signal`。
- allowed symbols 不被 KOL mentioned symbols 污染。

### 17.3 Integration Tests

- KOL context 不改 `CandidateScanService.saveBatchWithGate()` accepted/rejected。
- KOL boost 不讓 `isVetoed=true` 復活。
- KOL boost 不讓 `marketGrade=C` ENTER。
- KOL positive + priceGate BLOCK → 仍 BLOCK。
- Shadow log 有完整 trace。

### 17.4 Backtest Tests

- forward return 日期對齊。
- 無價格資料標 `NO_DATA`。
- would upgrade / would enter 統計正確。
- blocked_by_risk 後續表現可統計。

---

## 18. 最適 MVP

最小可交付：

1. DB 四張指定表：
   - `kol_theme_signal`
   - `kol_theme_stock_mapping`
   - `kol_signal_trace`
   - `theme_signal_evidence`
2. Manual raw input API。
3. AI structured result submit API。
4. Daily aggregate service（可先不建 aggregate table，用 query derived）。
5. Theme/Candidate read-only context API。
6. Claude/Codex prompt augmentation spec 實作。
7. Shadow-only score diff。
8. Dashboard API + simple UI wireframe。

不做：

- 自動爬 podcast / YouTube。
- 音訊轉錄。
- live final score。
- KOL 直接 candidate gate。
- KOL 直接 final decision。

---

## 19. 未來 Roadmap / Extensibility

### 19.1 Ingestion Sources

- YouTube transcript ingestion。
- Podcast RSS + transcript/ASR ingestion。
- Telegram / Discord monitor。
- PTT / Reddit theme scanner。
- News clustering。
- Broker report summaries。

### 19.2 Intelligence Layer

- Multi-KOL weighting。
- KOL credibility score。
- Source freshness / lag model。
- Theme lifecycle prediction。
- AI topic diffusion graph。
- Source recycling detection。
- Echo chamber / duplicated narrative detection。

### 19.3 Graph Model

未來可建立：

```text
Source → Theme → Stock → Evidence → Price Reaction → Outcome
```

支援：

- 哪個 KOL 常提早發現 theme。
- 哪些 source 常在行情末端才提。
- 哪些題材從 KOL 擴散到新聞再擴散到成交量。
- 哪些股票只是被提及但沒有量價確認。

---

## 20. Claude / Codex 架構審查後補強項

本規格完成後，另外讓 Claude 與 Codex 以 read-only 架構 reviewer 角度審查。兩者共同確認主方向正確：**獨立 table、shadow first、不接 FinalDecisionEngine、不污染 `theme_heat_score`**。同時提出以下實作前必須補強的硬要求。

### 20.1 Source Profile / Credibility 必須獨立建模

第一版四張表可先落地，但若要做 aggregation / scoring，建議 Phase 1.5 加：

```sql
CREATE TABLE IF NOT EXISTS kol_source_profile (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_name VARCHAR(120) NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    reliability_tier VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    source_weight DECIMAL(6,3) NOT NULL DEFAULT 0.500,
    bias_tags_json JSON NULL,
    is_paid_promotion_risk BOOLEAN NOT NULL DEFAULT FALSE,
    is_suspected_pump_risk BOOLEAN NOT NULL DEFAULT FALSE,
    historical_precision DECIMAL(6,3) NULL,
    historical_lead_time_days DECIMAL(6,3) NULL,
    notes VARCHAR(1000) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_kol_source_profile (source_name, source_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

用途：避免「最吵的 KOL」等於「最重要的訊號」。source weight 初期保守人工維護；未來再由 backtest 反饋。

### 20.2 Evidence Level 權重表

Aggregation 必須明確套用 evidence weight，不可讓 rumor 與 reasoned analysis 同權重。

```text
DIRECT_CLAIM       1.00  例如公司/法說/財報/官方直接資訊
REASONED_ANALYSIS  0.70  有邏輯、有供需/產業鏈論述
SECOND_HAND        0.40  二手轉述，需折扣
UNKNOWN            0.20  無法確認來源或原因
RUMOR              0.10  只可進 trace / risk，不應給正向 boost
PAID_PROMOTION     0.00  不給 boost，只保留風險 trace
SUSPECTED_PUMP     0.00  不給 boost，Codex review 必須標風險
```

### 20.3 Echo Chamber / Semantic Dedup

`content_hash` 只能擋完全重複，不能擋 KOL 互相改寫。Aggregation 必須至少做兩層折扣：

```text
exact_dedup: content_hash 相同 → 只算一次
near_dedup: 同 theme + 同 direction + 24h 內 + 高語意相似 → confirmation 權重折扣
cluster_discount: 同平台/同陣營/疑似互相轉述 → sourceDiversity 降權
```

MVP 可先不用 embedding，但 DB schema / trace 必須預留：

```json
{
  "dedup": {
    "contentHashMatched": false,
    "semanticClusterId": "optional-future",
    "clusterDiscountApplied": 0.5,
    "reason": "same theme/direction within 24h"
  }
}
```

### 20.4 Aggregation 公式要固定版本

每次 aggregate 必須寫入 `aggregation_version`，避免日後 replay 不可重現。初版建議：

```text
weighted_signal = source_weight
                * evidence_weight
                * direction_weight
                * confidence_score
                * freshness_decay
                * independent_source_discount

attention_score     = clamp10(sum(abs(weighted_signal)) scaled)
confirmation_score  = clamp10(unique_independent_sources_weighted)
freshness_score     = clamp10(avg(freshness_score * freshness_decay))
rotation_score      = clamp10(early_theme_score * non_consensus_weight)
crowding_risk_score = clamp10(max(crowding_risk, echo_chamber_risk, price_extended_risk))

kol_raw_boost = 0.35*attention
              + 0.25*confirmation
              + 0.25*freshness
              + 0.15*rotation
              - 0.35*crowding_risk

kol_boost_shadow = clamp(normalize(kol_raw_boost), -0.3, +0.2)
```

注意：這裡的 boost 是 **shadow score delta**，不是 final decision。

### 20.5 Anchoring 風險：Prompt 投放順序

Claude review 特別提醒：KOL context 會造成 AI anchoring。Phase 4 prompt augmentation 建議採兩階段：

1. **第一個月只接 Codex review**：讓 Codex 用 KOL context 檢查 FOMO / crowded / late-stage，不給 Claude research 增加看多偏誤。
2. 若要給 Claude，prompt 必須要求：
   - 先基於 price/volume/theme/fundamental 產生 base view。
   - 再讀 KOL context 做「confirm / contradict / overheated / noise」分類。
   - 不得讓 KOL context 改寫 `theme_strength` 數值。

### 20.6 Retention / Legal / Storage Policy

手動輸入 podcast / YouTube / KOL 長文本時，不應無限制保存全文。

建議：

```text
raw_content <= 64KB：可存 DB
raw_content > 64KB：落 file storage，DB 存 hash + path + summary
raw_content retention：90 天
ai_summary / mapping / evidence / trace：至少 1 年
source_url 公開展示：需保留來源但 dashboard 預設只顯示 source_name/title
```

### 20.7 Graduation Criteria

Shadow 不可無限期模糊存在，也不可過早 live。進 live soft boost 前需同時滿足：

```text
shadow trading days >= 60
有效 KOL theme samples >= 100
boosted group T+3 expectancy > baseline
boosted group MDD 不高於 baseline
FOMO false positive rate 不高於 baseline + 5%
high crowding risk 對應較差 forward return，證明風險分有效
所有 risk gate conflict 在 shadow 中都維持 blocked
人工 review 無重大跟單/追高事故
```

### 20.8 實作前新增測試護欄

除了第 17 節測試，還需補：

- `KolSourceProfileWeightTest`：付費/疑似 pump source 不給 boost。
- `KolEchoChamberDiscountTest`：同題材同方向 24h 內重複轉述不得線性加分。
- `KolEvidenceWeightTest`：RUMOR/SECOND_HAND 權重明確折扣。
- `KolPromptAnchoringGuardTest`：prompt 必須含「先 base view，再 external review」或「Codex-only review」guardrail。
- `KolRetentionPolicyTest`：超長 raw content 不直接塞 DB。

---

## 21. 最終建議

KOL / Podcast Theme Signal Engine 值得做，但必須嚴格放在 **外部題材雷達 / weak signal layer**。

最佳 integration point：

1. `kol_theme_signal*` tables 保存 raw + structured + trace。
2. `Theme Engine` 讀 daily aggregate context，但不直接覆寫 `theme_heat_score`。
3. `Candidate Engine` 只拿 context / shadow boost。
4. `Claude` 用它做研究提示。
5. `Codex` 用它做反向審查：crowding / FOMO / late-stage。
6. `FinalDecisionEngine` 初期完全不接 live。
7. Shadow / backtest 證明有效後，才允許 capped `+0.2` live soft boost。

核心安全句：

> KOL signal can explain why a theme is worth researching; it must never be the reason to enter a trade.
