# Java Trading System Architecture

最後更新：2026-04-24

本文件描述 `trading-system` Spring Boot 子系統架構。跨 AI 總架構與 Claude/Codex 分工請看 `../../docs/system-architecture.md`。

## 1. 分層原則

```text
controller  →  service/workflow  →  engine  →  repository/entity
scheduler   →  workflow/service
client      →  service
notify      ←  service/final decision
ai          →  parser/prompt only
```

硬規則：

- `client` 只打外部 API，不做交易判斷。
- `engine` 只做純規則計算，盡量無 Spring 依賴，必須容易 unit test。
- `service` 負責組裝資料、呼叫 engine、寫 DB、產 trace。
- `scheduler` 只做時間觸發與 job lock，不放複雜決策。
- `controller` 只做 API I/O，不放交易規則。
- `notify` 只做訊息組裝與送出，不自行改 decision。
- `ai` 只做 prompt、parser、adapter，不介入 live trading rule。

## 2. Package Responsibilities

| Package | Responsibility |
|---|---|
| `client` | TWSE/TPEx/TAIFEX/T86 外部 API adapter |
| `controller` | REST API、Dashboard、manual trigger |
| `scheduler` | cron、job lock、排程入口 |
| `workflow` | 盤前/盤中/盤後流程編排 |
| `service` | 業務 orchestration、持久化、report、bridge |
| `engine` | 純 Java 決策與計分規則 |
| `entity` | JPA entity |
| `repository` | Spring Data repository |
| `dto` | request/response/internal record |
| `domain/enums` | enum 與 domain value |
| `notify` | LINE template / message builder |
| `ai` | prompt/parser/adapter |
| `state` / `util` | 輔助狀態與工具 |

## 3. Main Runtime Flow

```text
Scheduler trigger
  → WorkflowService
  → data Client / Repository
  → Service builds input DTO
  → Engine calculates decision
  → Service persists entity and trace
  → Notify / Dashboard / API response
```

## 4. FinalDecision Pipeline

```text
Candidate inputs
  → AI readiness check
  → MarketRegime gate
  → ThemeStrength evaluation
  → StockRankingService
  → SetupValidationService
  → ExecutionTimingService
  → PortfolioRiskService
  → FinalDecisionEngine
  → ExecutionDecisionService
  → ThemeGate trace / shadow / optional live override
  → FinalDecisionEntity
```

Design goals：

- Market regime can block trading early.
- Held/cooldown/ranking are handled before setup.
- Setup and timing gates prevent score-only entries.
- Portfolio risk runs before final entry.
- Execution decision logs upstream trace IDs.
- Theme Engine v2 defaults to trace/shadow only unless explicitly enabled.

## 5. Theme Engine v2 Integration

Components：

| Component | Responsibility |
|---|---|
| `ThemeSnapshotService` | Read `theme-snapshot.json`, validate, freshness/fallback |
| `ClaudeThemeResearchParserService` | Read Claude theme research JSON |
| `ThemeContextMergeService` | Merge Codex snapshot + Claude context; Claude cannot overwrite strength |
| `ThemeGateTraceEngine` | 8-gate deterministic trace |
| `ThemeGateOrchestrator` | Load snapshot/research, build per-candidate gate inputs |
| `ThemeShadowModeService` | Persist legacy vs theme diff |
| `ThemeShadowReportService` | Generate JSON/Markdown daily report |
| `ThemeLiveDecisionService` | Optional live override; default disabled |
| `ThemeLineSummaryService` | Format summary only; default disabled and does not send LINE |

Feature flags：

| Key | Default | Effect |
|---|---:|---|
| `theme.engine.v2.enabled` | false | Theme snapshot usable |
| `theme.gate.trace.enabled` | false | Run G1-G8 trace |
| `theme.shadow_mode.enabled` | false | Persist shadow diff and report |
| `theme.live_decision.enabled` | false | BLOCK may remove legacy ENTER |
| `theme.live_decision.wait_override.enabled` | false | WAIT may remove selection only if true |
| `theme.line.summary.enabled` | false | Format theme summary |

## 6. AI Task Orchestration

```text
ai_task(PENDING)
  → Claude submit watcher/file bridge
  → CLAUDE_DONE
  → Codex claim
  → CODEX_RUNNING
  → Codex submit result
  → CODEX_DONE
  → FinalDecision / Notify
  → FINALIZED
```

Constraints：

- State transitions are monotonic.
- File bridge must ignore `.tmp`, unstable files, and `.processing` locks.
- FinalDecision must report AI readiness. Missing Claude/Codex is a traceable degradation, not silent success.

## 7. Scheduler Responsibilities

| Job | Responsibility |
|---|---|
| `PremarketDataPrepJob` | 盤前資料、AI task setup |
| `PremarketNotifyJob` | 盤前通知 |
| `OpenDataPrepJob` | 開盤資料 |
| `FinalDecision0930Job` | 09:30 final decision |
| `HourlyIntradayGateJob` | market gate refresh |
| `FiveMinuteMonitorJob` | 持倉監控 |
| `MiddayReviewJob` | 盤中檢討 |
| `AftermarketReview1400Job` | 今日交易檢討 |
| `PostmarketDataPrepJob` | 收盤與廣度資料 |
| `PostmarketAnalysis1530Job` | 盤後分析 |
| `WatchlistRefreshJob` | watchlist refresh |
| `T86DataPrepJob` | 法人籌碼 |
| `TomorrowPlan1800Job` | 明日計畫 |
| `WeeklyTradeReviewJob` | 週檢討與 benchmark |
| `ClaudeSubmitWatcherJob` | Claude file bridge watcher |
| `AiTaskSweepJob` | AI task timeout/sweep |
| `ExternalProbeHealthJob` / `DailyHealthCheckJob` | 健康檢查 |

## 8. Persistence Map

| Area | Main tables |
|---|---|
| AI orchestration | `ai_task` |
| Candidates/scoring | `candidate_stock`, `stock_evaluation`, `stock_ranking_snapshot` |
| Setup/timing/risk/execution | `setup_decision_log`, `execution_timing_decision`, `portfolio_risk_decision`, `execution_decision_log` |
| Market/theme | `market_snapshot`, `market_regime_decision`, `theme_strength_decision` |
| Theme Engine shadow | `theme_shadow_decision_log`, `theme_shadow_daily_report` |
| Portfolio | `position`, `watchlist_stock`, `daily_pnl` |
| Notification/config | `notification_log`, `score_config` |
| Final output | `final_decision`, `hourly_gate_decision`, `monitor_decision` |

## 9. Review Checklist

When reviewing changes：

1. Does it alter live decision behavior without a default-off feature flag?
2. Does any score bypass setup, timing, risk, or market regime gates?
3. Are legacy score and trace preserved?
4. Is the new rule in an engine, not hidden in controller/scheduler?
5. Are fallback and missing-data paths explicit?
6. Are DB writes idempotent where jobs may rerun?
7. Are tests covering both pass and block/wait paths?
