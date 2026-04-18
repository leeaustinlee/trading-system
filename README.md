# Trading Decision Platform

台股中短線交易決策平台（Java / Spring Boot 版）。

## 技術棧

- Java 17 / Spring Boot 3.4.4
- MySQL 8+ / JPA (ddl-auto:update)
- 115+ 單元 & 整合測試

## 架構

```
Scheduler（13 個定時任務，全部可開關）
  ↓
Workflow（流程編排）
  ↓
Service（業務邏輯） ←→ Engine（純計算，無 Spring 依賴）
  ↓
Repository → MySQL
  ↑
Client（TWSE MIS / TAIFEX / T86 外部 API）
  ↑
Notify（LINE Push API）
```

## 核心模組

### BC Sniper v2.0 評分管線
```
JavaStructureScoringEngine → ConsensusScoringEngine → VetoEngine (14 條)
  → WeightedScoringEngine → FinalDecisionEngine (A+ only 進場)
```

### Position Layer — 持股優先
- `PositionDecisionEngine`：每日/盤中持股決策（STRONG/HOLD/WEAKEN/EXIT/TRAIL_UP）
  - Drawdown 回撤監控、時間衰退、isExtended 分級、failedBreakout 快速 EXIT
  - Trailing stop 三階段自動上移
- `FiveMinuteMonitorJob`：盤中每 5 分鐘持倉停損/停利監控 + LINE 警報

### Watchlist Layer — 觀察名單
- `WatchlistEngine`：候選股連續追蹤（ADD/KEEP/PROMOTE_READY/DROP/EXPIRE）
  - Decay 時間衰退、marketGrade 過濾、READY 門檻分級
- 新倉優先來自 Watchlist READY，而非每天陌生新股

### 風控機制
- 滿倉禁止新倉（portfolio.max_open_positions）
- 同題材集中度限制（portfolio.same_theme_max）
- Score gap 保護 STRONG 持股不被輕易替換
- Entry trigger（突破/回測確認才進場）
- Cooldown：symbol + theme 維度冷卻
- Market-level cooldown：連續虧損或當日虧損超限 → 禁止交易
- 重複持倉防護（同 symbol 不可重複 OPEN）

### 題材評分
- `ThemeSelectionEngine`：market_behavior × 0.55 + heat × 0.25 + continuation × 0.20

## Quick Start

1. 準備 Java 17 與 MySQL 8+
2. 建立 DB：`trading_system`（local MySQL port 預設 `3330`）
3. 複製 `.env.example` 為 `.env`，填入實際值
4. 啟動：`./scripts/run-local.sh`

本地 UI Console：`http://localhost:8080/`

快速載入測試資料：
```bash
# application-local.yml 設定 trading.mock-data-loader.enabled: true
# 或手動載入：
./scripts/load-local-seed.sh
```

## 主要 API

| 類別 | 端點 |
|------|------|
| 儀表板 | `GET /api/dashboard/current` |
| 候選股 | `GET /api/candidates/current` |
| 持倉 | `GET /api/positions/open` |
| 決策 | `POST /api/decisions/final/evaluate` |
| 觀察名單 | `GET /api/watchlist/current`, `GET /api/watchlist/ready` |
| 持倉審查 | `POST /api/watchlist/position-review/trigger` |
| 損益 | `GET /api/pnl/summary` |
| 題材 | `GET /api/themes/snapshots` |
| 系統 | `GET /api/system/external/probe` |

完整 API 列表：`docs/api-spec.md`

## 排程（13 + 1 個 Job）

| 時間 | Job | 內容 |
|------|-----|------|
| 08:10 | PremarketDataPrepJob | 台指期 + 候選股昨收 |
| 08:30 | PremarketNotifyJob | 盤前分析 + Java 結構評分 |
| 09:01 | OpenDataPrepJob | 開盤價 + 跳空 |
| 09:30 | FinalDecision0930Job | 最終決策（A+ only） |
| 10:05-13:05 | HourlyIntradayGateJob | 整點行情 gate |
| 每 5 分鐘 | FiveMinuteMonitorJob | 大盤監控 + 持倉停損/停利 |
| 11:00 | MiddayReviewJob | 盤中戰情 |
| 14:00 | AftermarketReview1400Job | 交易檢討 |
| 15:05 | PostmarketDataPrepJob | 收盤價 + 市場廣度 |
| 15:30 | PostmarketAnalysis1530Job | 損益彙總 + 題材評分 |
| 15:35 | WatchlistRefreshJob | 觀察名單刷新 |
| 18:10 | T86DataPrepJob | 法人籌碼 |
| 18:30 | TomorrowPlan1800Job | 明日計畫 |
| 08:25/15:25 | ExternalProbeHealthJob | 外部服務健康檢查 |

## Config

40+ 參數透過 `score_config` 表管理，啟動時自動種植預設值：
- `scoring.*` — 評分權重與分級門檻
- `veto.*` — 淘汰規則
- `position.review.*` — 持倉決策（drawdown/時間衰退/trailing）
- `watchlist.*` — 觀察名單（decay/門檻/marketGrade）
- `trading.cooldown.*` — 冷卻（symbol/theme/market-level）
- `portfolio.*` — 倉位控制（滿倉/集中度/score gap）

API 查詢與即時修改：`GET/PUT /api/config/score/{key}`

## LINE 設定

所有 LINE 參數由 `.env` 控制：
- `LINE_ENABLED=false|true`
- `LINE_CHANNEL_ACCESS_TOKEN=...`
- `LINE_TO=...`

## 測試

```bash
mvn test
# 115 tests pass, 4 skipped (TAIFEX live)
```

## 文件

- `docs/spec.md` — 完整規格與進度
- `docs/api-spec.md` — API 列表
- `docs/architecture.md` — 分層原則
- `docs/scoring-workflow.md` — BC Sniper v2.0 評分流程
- `docs/claude-handoff.md` — 開發接手手冊
