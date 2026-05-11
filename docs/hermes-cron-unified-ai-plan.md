# Hermes Cron Unified AI Plan

目的：
- 只把 Claude / Codex 外部排程收斂到 Hermes 管理
- Java 內部 business scheduler / watcher / sweep 繼續保留在 Java
- 避免 Windows Task Scheduler、Cowork、Hermes 三邊同時控同一批 AI 任務，造成 task 狀態與輸出不一致

## 設計原則

1. Java 保留 source of truth
- `ai_task`
- `claude-research-request.json`
- `claude-submit/processed`
- `POST /api/ai/tasks/{id}/codex-result`
- LINE / Telegram 通知與最終 workflow

2. Hermes 只管「外部 AI 執行者」
- Claude：讀 prompt / request，寫 `claude-submit/*.json`
- Codex：等 `CLAUDE_DONE` 後提交 `codex-result`

3. 不把 Java 內部 watcher/sweep 搬出來
- 保留 `ClaudeSubmitWatcherJob`
- 保留 `AiTaskSweepJob`
- 保留 Java 交易流程排程

4. 同一 taskType 同一時段只允許一條主要執行路徑
- Hermes 成為唯一正式外部排程器
- 關閉 Windows Task Scheduler 的 Claude / Codex 任務
- 停用 Cowork recurring Claude task，若要保留只能當人工 disaster-recovery，不可常態並行

5. 路徑與帳號 routing
- 專案在 `/mnt/d/ai/...`
- Claude 一律走 personal route
- 自動化中明確用 `c-personal`
- 不依賴 cwd 自動判斷

## 保留在 Java 的排程

以下維持原樣，不搬 Hermes：

- `DailyHealthCheckJob`
- `PremarketDataPrepJob`
- `ExternalProbeHealthJob`
- `PremarketNotifyJob`
- `OpenDataPrepJob`
- `FinalDecision0930Job`
- `HourlyIntradayGateJob`
- `FiveMinuteMonitorJob`
- `MiddayReviewJob`
- `AftermarketReview1400Job`
- `PostmarketDataPrepJob`
- `PostmarketAnalysis1530Job`
- `WatchlistRefreshJob`
- `T86DataPrepJob`
- `TomorrowPlan1800Job`
- `WeeklyTradeReviewJob`
- `ClaudeSubmitWatcherJob`
- `AiTaskSweepJob`
- `PaperTrade*` 系列
- `MarketIndexDataPrepJob`
- `PremarketHealthAlertJob`

也就是說：Hermes 不接手 Java 的 business orchestration，只接手 Claude / Codex 外部 runner。

## Hermes 統一管理的目標 job

### Claude 5 條

| Hermes job name | Schedule | 對應 taskType | 實際動作 |
|---|---|---|---|
| `stock-ai-claude-premarket` | `20 8 * * 1-5` | `PREMARKET` | 執行 Claude prompt，寫 `claude-submit` |
| `stock-ai-claude-opening` | `20 9 * * 1-5` | `OPENING` | 同上 |
| `stock-ai-claude-midday` | `15 11 * * 1-5` | `MIDDAY` | Java `MiddayReviewJob` 11:00 建 request 後才可執行 |
| `stock-ai-claude-postmarket` | `18 15 * * 1-5` | `POSTMARKET` | 配合目前 Java `PostmarketDataPrepJob 15:05`，保留 13 分鐘緩衝後啟動 |
| `stock-ai-claude-tomorrow` | `18 18 * * 1-5` | `T86_TOMORROW` | 配合目前 Java `T86DataPrepJob 18:10`，延後至 18:18 |

### Codex 5 條

| Hermes job name | Schedule | 對應 taskType | 實際動作 |
|---|---|---|---|
| `stock-ai-codex-premarket` | `28 8 * * 1-5` | `PREMARKET` | 等 `CLAUDE_DONE` 後提交 codex-result |
| `stock-ai-codex-opening` | `28 9 * * 1-5` | `OPENING` | 同上 |
| `stock-ai-codex-midday` | `25 11 * * 1-5` | `MIDDAY` | 等 11:15 Claude 完成後提交 codex-result |
| `stock-ai-codex-postmarket` | `28 15 * * 1-5` | `POSTMARKET` | 同上 |
| `stock-ai-codex-tomorrow` | `28 18 * * 1-5` | `T86_TOMORROW` | 等 `CLAUDE_DONE` 後提交 codex-result；配合目前 Java `T86DataPrepJob 18:10` |

## 實作模式

不建議讓 Hermes cron prompt 內直接寫超長 shell command。
建議採用：

- Hermes cron = scheduler
- checked-in wrapper / script = executor

### Claude executor

沿用既有：
- `D:\ai\stock\run-claude-research.ps1`
- WSL 內等價路徑：`/mnt/d/ai/stock/run-claude-research.ps1`

但建議新增一個 WSL shell wrapper，讓 Hermes 在 WSL 內直接叫：
- `/mnt/d/ai/stock/scripts/hermes-run-claude.sh <premarket|opening|midday|postmarket|tomorrow>`

wrapper 職責：
1. 先確認 Java health `http://localhost:8888/actuator/health`
2. 驗證 `claude-research-request.json` 存在
3. 呼叫 `c-personal` 或既有 PowerShell 腳本
4. 驗證 `claude-submit/processed` 或 submit json / latest md 是否有更新
5. 輸出結構化摘要

### Codex executor

沿用既有：
- `D:\ai\stock\run-codex-v2-task.ps1`
- WSL 內等價路徑：`/mnt/d/ai/stock/run-codex-v2-task.ps1`

同樣建議新增：
- `/mnt/d/ai/stock/scripts/hermes-run-codex.sh <PREMARKET|OPENING|MIDDAY|POSTMARKET|T86_TOMORROW>`

wrapper 職責：
1. 驗證 Java health
2. 驗證本輪 task 已存在
3. 等待 / 檢查 `CLAUDE_DONE`
4. 執行 Codex runner
5. 驗證 `CODEX_DONE`、latest md、result json 有更新
6. 輸出結構化摘要

## 建議的 Hermes cron job 定義形態

### Claude job prompt 模板

Hermes cron prompt 應極小，只做：
- 呼叫 wrapper
- 讀 wrapper 輸出
- 只有失敗或異常才通知

範例概念：

```text
Run /mnt/d/ai/stock/scripts/hermes-run-claude.sh premarket.
Read its structured JSON summary.
If success and the expected submit/processed artifact was updated, respond with [SILENT].
If Java is down, request file missing, submit failed, or artifact unchanged, report a concise failure summary including taskType, reason, and log path.
```

### Codex job prompt 模板

```text
Run /mnt/d/ai/stock/scripts/hermes-run-codex.sh PREMARKET.
Read its structured JSON summary.
If success and CODEX_DONE/result artifacts were updated, respond with [SILENT].
If the task never reached CLAUDE_DONE, Java is down, or submit failed, report a concise failure summary including taskType, reason, and log path.
```

## 建議 deliver 策略

平常成功：
- `deliver: local` 或在 prompt 中要求 `[SILENT]`
- 避免每天 10 次成功通知洗版

失敗 / 異常：
- deliver 回你常用 chat thread
- 只在以下情況通知：
  - Java health fail
  - request file missing
  - Claude 未成功提交
  - Codex 等不到 `CLAUDE_DONE`
  - `CODEX_DONE` 未形成
  - wrapper exit 非 0

## 避免狀態不一致的關鍵規則

### 規則 1：Hermes 取代外部排程後，舊排程要停
必停：
- Windows Task Scheduler 內 5 個 Claude 任務
- Windows Task Scheduler 內 5 個 Codex 任務
- Cowork recurring Claude tasks

否則會發生：
- 同一時段多個 Claude 同時寫 submit
- 同一 task 多個 Codex 同時 submit
- latest markdown 被不同來源覆蓋
- processed / failed artifact 難以判讀

### 規則 2：Claude 與 Codex 仍透過 Java state machine 串接
- Claude 不直接打 Java 決策流程，只寫 file bridge
- Codex 不自己推進 Claude state，只讀 `CLAUDE_DONE`
- Java workflow 照舊是最終整合者

### 規則 3：Hermes job 要驗證 artifact，不只看 exit code
Claude 成功條件至少要驗：
- `claude-research-latest.md` 更新
- 或 `claude-submit/processed/*.processed.json` 出現 / 更新

Codex 成功條件至少要驗：
- task 進入 `CODEX_DONE`
- `codex-research-latest.md` 更新
- `codex-result-{taskId}.json` 更新

### 規則 4：T86 tomorrow 改成對齊目前 Java 時序
目前程式實際排程仍是 `T86DataPrepJob 18:10`。
因此 Hermes 已調整為：
- Claude tomorrow：`18:18`
- Codex tomorrow：`18:28`

這樣可以避免 legacy 17:50/17:58 在 request 或 task 尚未建立前就誤觸發，減少 `not-ready` 與狀態競爭。
若後續 Java 時序再調整，Hermes 也要同步改表，不可兩邊各自漂移。

## 建議的 Hermes job 命名規範

- `stock-ai-claude-premarket`
- `stock-ai-claude-opening`
- `stock-ai-claude-midday`
- `stock-ai-claude-postmarket`
- `stock-ai-claude-tomorrow`
- `stock-ai-codex-premarket`
- `stock-ai-codex-opening`
- `stock-ai-codex-midday`
- `stock-ai-codex-postmarket`
- `stock-ai-codex-tomorrow`

## 建議的工作目錄與 toolsets

### workdir
- `/mnt/d/ai/stock/trading-system`

### Claude jobs enabled_toolsets
- `terminal`
- `file`

### Codex jobs enabled_toolsets
- `terminal`
- `file`

因為真正工作在 wrapper 內完成，Hermes 不需要 web/browser 等多餘 toolset。

## 建議的 cron 建立方式

每條 job 建議：
- `enabled_toolsets: ["terminal", "file"]`
- `workdir: /mnt/d/ai/stock/trading-system`
- prompt 只負責呼叫 wrapper + 讀摘要
- 若 wrapper 很長，優先放到 `script` 或 checked-in shell wrapper，不要把大段 command 直接塞進 cron prompt

## 遷移步驟

### Phase 0：先做 wrapper
1. 建 `scripts/hermes-run-claude.sh`
2. 建 `scripts/hermes-run-codex.sh`
3. 每支 wrapper 都輸出結構化 JSON
4. 先手動測通 5 個 Claude / 5 個 Codex 模式

### Phase 1：Hermes 建 job，但先不開
1. 建 10 條 Hermes cron job
2. `deliver: local`
3. 先手動 `cron run` 驗證

### Phase 2：停用舊 scheduler
1. Disable Windows Claude 5 jobs
2. Disable Windows Codex 5 jobs
3. Pause / remove Cowork recurring Claude jobs

### Phase 3：切正式
1. 啟用 Hermes 10 jobs
2. 觀察 2~3 個交易日
3. 再決定是否把失敗通知送回 Telegram / 當前 chat

## 我建議的最終責任邊界

### Java
- 建 task
- 管 state machine
- file bridge watcher
- 最終 workflow / notification
- health / sweep / monitor

### Hermes
- 統一調度外部 Claude / Codex runner
- 管時間
- 管失敗通知
- 管執行紀錄與 wrapper summary

### Claude
- 只負責研究與 file bridge submit

### Codex
- 只負責在 `CLAUDE_DONE` 後提交 codex-result

## 不建議的做法

1. Hermes 直接取代 Java `ClaudeSubmitWatcherJob`
- 會把 file bridge state machine 拆散

2. Hermes 直接取代 `AiTaskSweepJob`
- timeout / catch-up 要貼著 app state 跑，放 Java 更合理

3. 保留 Windows / Cowork 與 Hermes 並行
- 很容易產生 duplicate run 與 latest 檔覆蓋

4. 讓 Hermes 直接用長 inline command 呼叫 `claude` / `codex`
- 不利除錯與版本控制
- wrapper 更穩

## 下一步建議

1. 先實作兩個 wrapper
- `scripts/hermes-run-claude.sh`
- `scripts/hermes-run-codex.sh`

2. 再由 Hermes 建立 10 條 job 草案

3. 最後補一份 migration checklist
- 哪些 Windows Task Scheduler 要停
- 哪些 Cowork task 要停
- 哪些成功條件要驗
