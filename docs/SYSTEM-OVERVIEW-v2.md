# Austin 台股系統 v2 — 完整流程入口文件

> **地位**：本檔是 Java / Claude / Codex 三方共用的系統入口。任何流程、時程、API、職責邊界調整後，都必須同步更新這份檔案。衝突時以本文件為準。
>
> **位置**：`D:\ai\stock\SYSTEM-OVERVIEW-v2.md`（WSL：`/mnt/d/ai/stock/SYSTEM-OVERVIEW-v2.md`）
>
> **最後更新**：2026-04-20（持倉警報降噪 + 來源統一 Trading System）

---

## 🏗️ 系統架構

```
 ┌─────────────────────────────────────────────────────────────┐
 │  Windows host                                               │
 │  ├─ MySQL :3330                                             │
 │  ├─ Codex agent (本機，直接 POST localhost:8080)            │
 │  └─ Task Scheduler (Codex-xxx × 5 個)                       │
 │                       ↕                                     │
 │  ┌─────────────────────────────────────────────────────────┐│
 │  │  WSL                                                    ││
 │  │  Java App :8080 ← 系統中樞                              ││
 │  │  ├─ 16 個 @Scheduled Job（cron MON-FRI）                ││
 │  │  ├─ AiTaskService（PENDING→CLAUDE_DONE→CODEX_DONE→FINALIZED）│
 │  │  ├─ FileBridgeWatcher（每 30 秒掃 claude-submit/）      ││
 │  │  └─ LineTemplateService（載 .env 發 LINE）              ││
 │  └─────────────────────────────────────────────────────────┘│
 └─────────────────────────────────────────────────────────────┘
            ↑
    Claude (cloud sandbox)
    寫 /mnt/d/ai/stock/claude-submit/*.json
```

---

## 📅 每日時程（MON-FRI 依序）

| 時間 | 執行者 | 動作 |
|---|---|---|
| **08:00** | Java `DailyHealthCheckJob` | 檢查昨日 15 個 step 是否完成，缺漏 LINE 警報 |
| **08:10** | Java `PremarketDataPrepJob` | 抓台指期夜盤；若昨日無候選→fallback 最新交易日；**建 `PREMARKET` ai_task (PENDING)** |
| 08:20 | Claude (外部) | 寫 `D:\ai\stock\claude-submit\claude-PREMARKET-YYYY-MM-DD-HHMM.json` |
| ~08:20+30s | Java `ClaudeSubmitWatcher` | 掃到檔 → `submitClaudeResult` → 狀態變 `CLAUDE_DONE`；rename `.processed.json` |
| **08:25** | Java `ExternalProbeHealthJob` | 探測 TWSE / TAIFEX 健康 |
| **08:28** | Codex | 查 `CLAUDE_DONE` PREMARKET task → 補 MIS 報價 → 決策 → `POST /ai/tasks/{id}/codex-result` |
| **08:30** | Java `PremarketNotifyJob` | 讀 task.codexResultMarkdown 發 LINE ×2（主決策 + 📎 AI 研究） |
| **09:01** | Java `OpenDataPrepJob` | 抓開盤資料；可建 `OPENING` task（或由 File Bridge 自動建） |
| 09:05–13:05 | Java `HourlyIntradayGateJob` | 整點閘，市場等級變化才發 LINE |
| 09:00–13:30 每 5 分 | Java `FiveMinuteMonitorJob` | 持倉關鍵價監控，觸發事件才發 LINE |
| 09:20 | Claude | 寫 `claude-OPENING-*.json` → Bridge auto submit |
| 09:28 | Codex | 接 OPENING CLAUDE_DONE |
| **09:30** | Java `FinalDecision0930Job` | FinalDecisionService 跑 A+ 門檻；LINE ×2 |
| 10:50 | Claude | 寫 `claude-MIDDAY-*.json` |
| 10:58 | Codex | 接 MIDDAY |
| **11:00** | Java `MiddayReviewJob` | 盤中戰情 LINE ×2 |
| **14:00** | Java `AftermarketReview1400Job` | Codex-only 盤後檢討 LINE |
| **15:05** | Java `PostmarketDataPrepJob` | 抓收盤 + 法人 |
| 15:20 | Claude | 寫 `claude-POSTMARKET-*.json` |
| 15:25 | Java `ExternalProbeHealthJob` | 第二次健康檢查 |
| **15:28** | Codex | 接 POSTMARKET + **🔴 `POST /api/candidates/batch` 寫明日候選** |
| **15:30** | Java `PostmarketAnalysis1530Job` | 盤後 LINE ×2 |
| **15:35** | Java `WatchlistRefreshJob` | 刷新觀察清單 |
| **18:10** | Java `T86DataPrepJob` | 補 T86 三大法人當日資料 |
| 17:50 | Claude | 寫 `claude-T86_TOMORROW-*.json` |
| 17:58 | Codex | 接 T86_TOMORROW |
| **18:30** | Java `TomorrowPlan1800Job` | 明日策略 LINE ×2 |
| 19:00 週五 | Java `WeeklyTradeReviewJob` | 週度交易檢討 + 策略建議 |

---

## 🎭 三方邊界（硬規則）

| 動作 | Java | Claude | Codex |
|---|:---:|:---:|:---:|
| 建 ai_task | ✅ cron 建 | ❌（但 File Bridge 找不到會自動建 PENDING） | ❌ |
| 寫 `claude-research-latest.md` | ❌ | ✅ | ❌ |
| 寫 `/claude-submit/*.json` | ❌ | ✅ | ❌ |
| `POST /claude-result` | ❌（Bridge 代替） | Bridge 代做 | ❌ |
| `POST /codex-result` | ❌ | ❌ | ✅ |
| 寫 `codex-research-latest.md` | ❌ | ❌ | ✅ |
| 寫 DB 候選 `POST /candidates/batch` | ❌ | ❌ | ✅（盤後必做） |
| 發 LINE | ✅ 唯一 | ❌ | ❌ |
| 讀 TWSE MIS 即時報價 | ✅ | ✅ | ✅ |
| 做最終決策 | ✅ FinalDecisionService | ❌ 只給研究意見 | ✅ 可 veto + markdown |

---

## 🗝️ 關鍵檔案 / 端點

### Java 核心檔案

- `config/LocalMockDataLoader.java` — `enabled=false`（正式跑後不再 seed）
- `scheduler/ClaudeSubmitWatcherJob.java` — 每 30 秒掃 `/mnt/d/ai/stock/claude-submit/`
- `service/ClaudeSubmitBridgeService.java` — 找不到 task 自動建
- `scheduler/PremarketDataPrepJob.java` — 08:10 建 PREMARKET task + 候選 fallback
- `workflow/IntradayDecisionWorkflowService.java` — 09:30 決策 + 補發 AI 摘要 LINE
- `service/AiTaskService.java` — `findLatestMarkdown()` helper（各 workflow 補發 LINE 共用）
- `.env` — `LINE_ENABLED=true / LINE_CHANNEL_ACCESS_TOKEN=... / LINE_TO=...`

### 外部檔案

- `D:\ai\stock\claude-research-latest.md` — Claude 最新研究（Claude 寫，Codex 讀）
- `D:\ai\stock\codex-research-latest.md` — Codex 最新決策（Codex 寫，Claude 讀）
- `D:\ai\stock\market-snapshot.json` — 共同快照（Codex 更新）
- `D:\ai\stock\claude-submit\*.json` — File Bridge 入口（Claude 寫，Watcher 處理後 rename `.processed.json`）
- `D:\ai\stock\capital-summary.md` — 資金 / 持倉總表（手動更新）
- `D:\ai\stock\codex-role-v2-prompt.md` — Codex v2 執行規格（入口）
- `D:\ai\stock\run-codex-v2-task.ps1` — Codex v2 runner（Windows 排程呼叫）
- `D:\ai\stock\codex-v2-task.md` — Codex v2 任務實作規格
- `D:\ai\stock\SYSTEM-OVERVIEW-v2.md` — **本檔（系統入口文件）**

### Codex v2 Windows 排程

v1 PowerShell 通知任務已全部 **Disabled**（保留歷史不刪）：
`AustinStock*`、`CodexDataPrep-*`、`ClaudeBridge-*`、`AustinTaiwanStockMonitor`、`AustinStockHourlyIntradayGate`、`AustinStockT86Confirm1810`

v2 新排程（5 個，MON-FRI）：

| 任務名 | 時間 | Type |
|---|---:|---|
| `Codex-Premarket-0828` | 08:28 | `PREMARKET` |
| `Codex-Opening-0928` | 09:28 | `OPENING` |
| `Codex-Midday-1058` | 10:58 | `MIDDAY` |
| `Codex-Postmarket-1528` | 15:28 | `POSTMARKET` |
| `Codex-TomorrowPlan-1758` | 17:58 | `T86_TOMORROW` |

排程動作統一為：
```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "D:\ai\stock\run-codex-v2-task.ps1" -Type <TYPE>
```

### 關鍵 API

```
GET  /api/ai/tasks                           今日所有 task
GET  /api/ai/tasks/{id}                      單筆詳情（含 claudeResultMarkdown / codexResultMarkdown）
POST /api/ai/tasks/{id}/claim                認領 PENDING → CLAUDE_RUNNING
POST /api/ai/tasks/{id}/claude-result        提交 Claude 結果（Bridge 代做）
POST /api/ai/tasks/{id}/codex-result         提交 Codex 結果
POST /api/ai/tasks/{id}/finalize             標記 FINALIZED
POST /api/ai/tasks/{id}/fail                 標記 FAILED（超時用）

GET  /api/candidates/current                 今日候選股
POST /api/candidates/batch                   批次寫候選（Codex 盤後必做）
DELETE /api/candidates/by-date/{date}        Admin cleanup
PUT  /api/candidates/{symbol}/ai-scores      AI 評分回填（Claude/Codex 用）

GET  /api/positions/open                     當前持倉
GET  /api/positions/live-quotes              持倉即時報價（空 symbols 自動撈 open positions）
POST /api/positions                          新增持倉
PATCH /api/positions/{id}                    編輯（停損停利 / 加減碼）
POST /api/positions/{id}/close               出清持倉

GET  /api/dashboard/current                  儀表板全貌
POST /api/scheduler/trigger/{key}            手動觸發（?force=true 覆寫 DONE）
GET  /api/scheduler/jobs                     所有 scheduled jobs 狀態
GET  /api/scheduler/logs?limit=50            執行歷史
GET  /api/orchestration/today                今日 15 step 狀態
GET  /api/orchestration/recent?days=7        最近 N 日 orchestration
POST /api/orchestration/recover?date=...     補跑缺漏 step
```

---

## 📨 LINE 通知模式

每個時段發 **2 則**：

1. **主決策**（`LineMessageBuilder.buildXxx()`）— 結構化內容
2. **📎 AI 研究摘要** — 來自 `task.codexResultMarkdown`（優先）或 `task.claudeResultMarkdown`；> 3500 字截斷

### AI 狀態標記（附加到標題列）

| task.status | 標籤 |
|---|---|
| `FINALIZED` / `CODEX_DONE` | ✅ 已整合 Codex 最終決策 |
| `CLAUDE_DONE` | ⏳ Claude 完成，Codex 尚未審核（Codex token 耗盡時就是這個） |
| `CLAUDE_RUNNING` | ⏳ Claude 研究中 |
| `PENDING` | ⚠ AI 任務尚未認領 |
| `FAILED` | ❌ AI 流程失敗 |

---

## 🛡️ 風控門檻

| 規則 | 預設值 | 由誰強制 |
|---|---|---|
| `final_decision.require_claude` | true | Claude 未完成 → REST 降級 |
| `final_decision.require_codex` | **false** | Codex 未完成仍發 LINE（降級用 Claude md） |
| A+ 進場門檻 | `final_rank_score ≥ 8.8 且 RR ≥ 2.5` | FinalDecisionEngine |
| 持倉滿倉 | `portfolio.max_open_positions = 3` | FinalDecisionService |
| 槓桿 ETF 上限 | 可操作資金 50% | Codex 手動檢查 |
| `veto.require_theme` | true | 無 themeTag 自動 veto |
| `veto.high` | false (暫關) | 當日 Claude 分數不齊時不自動 veto |
| 全市場冷卻 | 當日虧損 2% 或連虧 2 筆 | MarketCooldownService |
| 單檔資金 | 3-5 萬 | 手動檢查 |

### 持倉警報降噪規則（2026-04-20 新增）

| 規則 | 實作位置 | 行為 |
|---|---|---|
| `monitorMode=OFF` 不發持倉 LINE | `FiveMinuteMonitorJob` | Review log 仍寫，但不呼叫 `notifyPositionAlert` |
| 即時報價不可用不更新 trailing | `PositionReviewService` | quote null 或 unavailable → status 強制 HOLD、不更新 `trailingStopPrice` |
| POSITION_ALERT cooldown | `LineTemplateService` | 同 `symbol + status` 120 分鐘內不重發（`sendAndLog` 查 `notification_log`） |
| SYSTEM_ALERT cooldown | `LineTemplateService` | 30 分鐘 |
| MONITOR_5M cooldown | `LineTemplateService` | 15 分鐘 |
| LINE 來源統一 | `LineTemplateService.ensureSource()` | 所有 `來源：Codex / Claude / Codex+Claude` 發送前替換為 `來源：Trading System` |
| trailing stop reason 區分 | `PositionDecisionEngine` | `effectiveStop == trailingStopPrice` 時 reason = 「跌破移動停利」，否則 = 「觸發停損」 |

---

## 🔧 維運指令

### 啟動（必須載 .env，否則 LINE 靜默失效）

```bash
cd /mnt/d/ai/stock/trading-system
set -a; source .env; set +a
SPRING_PROFILES_ACTIVE=local nohup java -Xms512m -Xmx1536m \
  -jar target/trading-system-0.0.1-SNAPSHOT.jar >> logs/app.log 2>&1 &
disown
```

### 停止

```bash
pkill -9 -f "trading-system-0.0.1-SNAPSHOT.jar"
```

### 改 code 後

```bash
mvn package -DskipTests
# 然後重跑上面「啟動」指令
```

### 檢查當日健康

```bash
curl -s http://localhost:8080/api/orchestration/today
curl -s http://localhost:8080/api/ai/tasks
curl -s http://localhost:8080/api/dashboard/current | jq '.finalDecision'
```

### 補跑某 step（`?force=true` 可覆寫 DONE）

```bash
curl -X POST "http://localhost:8080/api/scheduler/trigger/final-decision?force=true"
# 可用 key:
# premarket-data-prep, premarket, open-data-prep, final-decision,
# hourly-gate, midday-review, aftermarket-review, postmarket-data-prep,
# postmarket, watchlist-refresh, t86-data-prep, tomorrow-plan,
# external-probe-health, daily-health-check, weekly-review
```

### Admin cleanup

```bash
# 刪除指定日期的候選 + stock_evaluation（清理 mock 或錯誤資料用）
curl -X DELETE "http://localhost:8080/api/candidates/by-date/2026-04-17"
```

---

## ✅ 當日驗收標準

跑完一個交易日後檢查：

```bash
GET /api/ai/tasks
```

預期：
```
PREMARKET      FINALIZED
OPENING        FINALIZED
MIDDAY         FINALIZED
POSTMARKET     FINALIZED
T86_TOMORROW   FINALIZED
```

**LINE 預期收到 10 則**（5 時段 × 2 則），每則署名 `來源：Codex` 或 `📎 AI 研究摘要`。

**隔日 08:10** `PremarketDataPrepJob` 的 `task.targetCandidatesJson` 應 = 前一日 15:28 Codex 寫進 DB 的候選。

---

## ⚠️ 已知待補（未來工作）

| 項目 | 優先級 | 說明 |
|---|---|---|
| 24/7 持久化 | P0 | systemd + Windows Task Scheduler 開機啟 WSL（memory 已記） |
| 券商下單整合 | P0 | 目前 paper trading，無 `OrderExecutionService` |
| 風控 kill-switch 落地 | P0 | `trading_status.allow_trade` 目前仍在 CLAUDE.md 紙上 |
| AI task timeout sweep | P0 | `CLAUDE_RUNNING` 超時無自動 fail |
| 持倉自動對帳 | P0 | 持倉手動寫 md，未跟券商庫存對帳 |
| 回測歷史資料載入 | P1 | BacktestEngine 骨架有，但無 `HistoricalDataLoader` |
| 外部 API 降級策略 | P1 | TWSE MIS 失敗只回 500，無重試/熔斷 |
| 策略版本化 | P1 | score-config 改動無版本快照 |
| Metrics + alert | P2 | Prometheus / alert rules 缺 |
| MySQL backup | P2 | 無備份策略 |

---

## 📝 變更歷史

| 日期 | 變更 | commit / PR |
|---|---|---|
| 2026-04-20 | **持倉警報降噪 + 來源統一 Trading System**：monitorMode=OFF 不發持倉 LINE、quote 不可用不更新 trailing、LINE 全部署名 Trading System、hit trailingStop 時 reason 區分「跌破移動停利」；新增 `hitTrailingStop` 單元測試 | 60e0c17 |
| 2026-04-20 | **Codex v2 PowerShell 遷移完成**：v1 排程全 Disable，建立 5 個 Codex-xxx-HHMM runner + `run-codex-v2-task.ps1`；AI_RULES_INDEX / codex-role-v2-prompt / codex-handoff 同步更新 | Codex 側（D:\ 檔案） |
| 2026-04-20 | 候選即時報價 last-price cache（30 秒內 MIS null 不跳昨收）| f4dd54e |
| 2026-04-20 | docs/SYSTEM-OVERVIEW-v2.md 納入 git 追蹤 | 7a997d0 |
| 2026-04-20 | 初版建立（架構圖、時程表、三方邊界、風控門檻） | f5cd020 (UI 二輪改進) |
| 2026-04-20 | Java File Bridge 上線（Claude 不能連 localhost 的方案） | d26b78a |
| 2026-04-20 | IntradayDecision + Midday/Postmarket/TomorrowPlan 皆補發 AI 摘要 LINE | 707b10d |
| 2026-04-20 | LocalMockDataLoader enabled=false；候選改由 Codex 盤後 POST | 8b27d79 |
| 2026-04-20 | candidates fallback（昨日空→最新交易日） | 431edeb |
| 2026-04-19 | PremarketDataPrepJob 建 task；Workflow LINE 讀 codex md | 1161f63 |
| 2026-04-19 | PR-1 冪等 + PR-2 AI Task Queue + PR-3 manual triggers | 6480155 |

---

## 🔄 更新本文件的規則

**任何以下事件後必須 edit 這份檔案**：

1. Java 新增 / 刪除 / 改時間任一 `@Scheduled` Job
2. 新增 / 改動 `/api/` 端點
3. Claude / Codex / Java 三方邊界有改變
4. 新增風控規則或門檻
5. 新增 / 刪除外部檔案路徑
6. 啟動 / 停止指令有變
7. 已知待補事項完成或變更優先級

**更新流程**：

1. 改上方對應章節
2. 在「變更歷史」加一列（日期 + 變更描述 + commit hash）
3. 若是 code 改動一併 `git commit -am "..."`
4. 若影響 Claude / Codex agent 行為，同步更新：
   - `D:\ai\stock\CLAUDE.md`
   - `D:\ai\stock\CODEX.md`
   - `D:\ai\stock\codex-role-v2-prompt.md`
   - `D:\ai\stock\AI_RULES_INDEX.md`

---

**GitHub**：https://github.com/leeaustinlee/trading-system

**最新 commit**：`f5cd020` (2026-04-20 UI 第二輪改進)
