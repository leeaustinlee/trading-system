# Trading Decision Platform

Java 17 / Spring Boot 3.4 台股中短線交易決策平台。此子專案負責排程、外部資料、AI task orchestration、規則 engine、DB persistence、Dashboard、LINE 通知與 decision trace。

完整跨 AI 架構見：`../docs/system-architecture.md`。

## 技術棧

- Java 17
- Spring Boot 3.4.x
- MySQL 8+
- Spring Data JPA
- Maven
- LINE Messaging API
- TWSE / TPEx / TAIFEX / T86 外部資料 adapter

## 分層架構

```text
Controller / Dashboard API
  ↓
Scheduler / Workflow
  ↓
Service orchestration
  ↓
Engine pure rules
  ↓
Repository / MySQL

Client adapters feed market data into services.
Notify builds LINE messages from persisted decisions and traces.
AI parser/prompt components only parse or format AI payloads; they do not own live rules.
```

Package responsibility：

| Package | Responsibility |
|---|---|
| `client` | 外部 API adapter，只取資料不做交易判斷 |
| `controller` | REST API / Dashboard |
| `scheduler` | cron trigger、手動 trigger、job lock |
| `workflow` | 盤前、盤中、盤後、watchlist 流程編排 |
| `service` | 業務組裝、DB 寫入、engine 呼叫、report/file bridge |
| `engine` | 純規則計算，可 unit test，避免 Spring 依賴 |
| `entity` / `repository` | JPA persistence |
| `dto` | request / response / internal records |
| `domain/enums` | 市場、策略、題材、決策 enum |
| `notify` | LINE 文案、template、push adapter |
| `ai` | AI prompt/parser/adapter，不放 live trading rules |

## 核心決策 pipeline

### 09:30 Final Decision

```text
CandidateStock / AI research
  → MarketRegimeService
  → ThemeStrengthService
  → StockRankingService
  → SetupValidationService
  → ExecutionTimingService
  → PortfolioRiskService
  → FinalDecisionEngine
  → ExecutionDecisionService
  → FinalDecisionEntity / LINE / Dashboard
```

保證：score alone 不能繞過 setup、timing 或 risk gate。

### Setup / Momentum

```text
候選股 → Claude + Codex research
  ├─ Setup pipeline: ranking + setup + timing + risk
  └─ Momentum pipeline: momentum candidate/scoring + stricter size/risk control
```

Momentum 是並行策略，不是 Setup fallback。實際啟用需看 config flag 與當前程式設定。

### Theme Engine v2

```text
ThemeSnapshotService
  → ClaudeThemeResearchParserService
  → ThemeContextMergeService
  → ThemeGateTraceEngine (G1-G8)
  → ThemeShadowModeService / ThemeShadowReportService
  → ThemeLiveDecisionService (flag off by default)
```

8 gates：

1. market_regime
2. theme_veto
3. theme_rotation
4. liquidity
5. score_divergence
6. RR
7. position_sizing
8. final_rank

Live override 安全規則：

- `theme.live_decision.enabled=false` 預設關。
- 開啟後只有 Theme `BLOCK` 能移除 legacy `ENTER`。
- Theme `WAIT` 預設 pass-through，除非 `theme.live_decision.wait_override.enabled=true`。
- Legacy final decision code、legacy merged symbols 與 override trace 必須保留。

## 主要 Engine 群組

| 群組 | Engine |
|---|---|
| Scoring | `JavaStructureScoringEngine`, `ConsensusScoringEngine`, `WeightedScoringEngine`, `StockEvaluationEngine` |
| Veto / Final | `VetoEngine`, `FinalDecisionEngine`, `DecisionLockEngine` |
| Ranking / Setup | `StockRankingEngine`, `SetupEngine`, `ExecutionTimingEngine` |
| Risk / Execution | `PortfolioRiskEngine`, `PositionSizingEngine`, `CapitalAllocationEngine`, `ExecutionDecisionEngine` |
| Position / Watchlist | `PositionDecisionEngine`, `PositionManagementEngine`, `WatchlistEngine`, `CooldownEngine` |
| Market / Theme | `MarketGateEngine`, `MarketRegimeEngine`, `ThemeSelectionEngine`, `ThemeStrengthEngine`, `ThemeGateTraceEngine` |
| Momentum | `MomentumCandidateEngine`, `MomentumScoringEngine`, `ChasedHighEntryEngine` |
| Review / Learning | `TradeReviewEngine`, `TradeAttributionEngine`, `BenchmarkAnalyticsEngine`, `StrategyRecommendationEngine`, `ExitRegimeIntegrationEngine` |
| Monitoring | `HourlyGateEngine`, `MonitorDecisionEngine`, `StopLossTakeProfitEngine`, `PriceGateEvaluator` |

## AI orchestration

`ai_task` is the source of truth for Claude/Codex workflow.

```text
PENDING
  → CLAUDE_RUNNING
  → CLAUDE_DONE
  → CODEX_RUNNING
  → CODEX_DONE
  → FINALIZED
```

Rules：

- Claude file bridge writes `.tmp` then renames to stable `.json`.
- Watcher ignores unstable `.tmp` and `.processing` locked files.
- Codex runner should claim `/claim-codex` before submit `/codex-result`.
- FinalDecision must expose AI readiness and fallback reason if one side is missing.

## Scheduler overview

Jobs are controlled by config and scheduler endpoints. Check actual enabled state in `application-local.yml`, DB config, or `SchedulerController`.

| Job | Purpose |
|---|---|
| `PremarketDataPrepJob` | 盤前資料準備、market snapshot、AI task |
| `PremarketNotifyJob` | 盤前通知 |
| `OpenDataPrepJob` | 開盤資料 |
| `FinalDecision0930Job` | 09:30 最終決策 |
| `HourlyIntradayGateJob` | 盤中 market gate |
| `FiveMinuteMonitorJob` | 5 分鐘持倉監控 |
| `MiddayReviewJob` | 盤中檢討 |
| `AftermarketReview1400Job` | 14:00 檢討 |
| `PostmarketDataPrepJob` | 盤後收盤資料與廣度 |
| `PostmarketAnalysis1530Job` | 盤後分析 |
| `WatchlistRefreshJob` | Watchlist refresh |
| `T86DataPrepJob` | 三大法人資料 |
| `TomorrowPlan1800Job` | 明日計畫 |
| `WeeklyTradeReviewJob` | 週檢討、歸因、benchmark |
| `ClaudeSubmitWatcherJob` | Claude file bridge watcher |
| `AiTaskSweepJob` | AI task cleanup / timeout handling |
| `ExternalProbeHealthJob` / `DailyHealthCheckJob` | 外部服務與系統健康檢查 |

## API groups

| Group | Controller |
|---|---|
| Dashboard | `DashboardController` |
| Market | `MarketController` |
| Candidates | `CandidateController` |
| Decisions | `DecisionController` |
| Positions | `PositionController` |
| Watchlist | `WatchlistController` |
| Themes | `ThemeController` |
| AI / Orchestration | `AiController`, `AiTaskController`, `OrchestrationController` |
| Workflow / Scheduler | `WorkflowController`, `SchedulerController` |
| Config | `ScoreConfigController` |
| Review / Strategy | `TradeReviewController`, `StrategyRecommendationController`, `BacktestController` |
| System | `SystemController` |
| Notifications | `NotificationController` |

完整 API 以 `docs/api-spec.md` 和 controller 程式碼為準。

## Config

Runtime parameters are stored in `score_config` and seeded by `ScoreConfigService`.

Important namespaces：

- `scoring.*`
- `veto.*`
- `ranking.*`
- `setup.*`
- `portfolio.*`
- `position.*`
- `watchlist.*`
- `momentum.*`
- `theme.*`
- `learning.*`
- `risk.*`

Query/update：`GET/PUT /api/config/score/{key}`。

## Quick Start

1. Install Java 17 and MySQL 8+.
2. Create database `trading_system`.
3. Create `.env` from local template or existing environment.
4. Start local server:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Dashboard：`http://localhost:8080/`

Health check：

```bash
curl -sS http://localhost:8080/actuator/health
```

## Tests

```bash
mvn test
```

For targeted review, prefer running the changed engine/service tests plus one final-decision integration/replay test.

## Documentation

| File | Purpose |
|---|---|
| `../docs/system-architecture.md` | Cross-system architecture and AI roles |
| `docs/architecture.md` | Java architecture and package rules |
| `docs/api-spec.md` | API reference |
| `docs/db-schema.md` | DB schema notes |
| `docs/scoring-workflow.md` | Scoring and decision flow |
| `docs/scheduler-plan.md` | Scheduler plan |
| `docs/runbook.md` | Operations runbook |
| `docs/claude-handoff.md` | Claude handoff |
| `docs/codex-handoff.md` | Codex handoff |
