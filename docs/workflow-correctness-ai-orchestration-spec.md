# Workflow Correctness 與 AI Orchestration 一致性 Spec

狀態：Draft for implementation  
適用範圍：`trading-system` Java workflow、`ai_task`、Claude file bridge、Codex API submit、FinalDecision、固定排程通知  
目標版本：v2.1 workflow correctness

## 1. Spec 需要修改的章節清單

本次規格變更聚焦四個 P0++ 問題：

1. AI task 狀態機不嚴格，狀態可倒退。
2. Claude file bridge 可能讀到尚未寫完的 JSON。
3. POSTMARKET / T86_TOMORROW task 建立時序錯誤，導致 AI 不可能準時完成。
4. FinalDecision 預設未強制等待 Codex，造成「Claude 研究 -> Codex 審核 -> Java 決策」鏈不完整。

需要同步更新的文件：

| 文件 | 修改內容 |
|---|---|
| `docs/spec.md` | 新增 v2.1 workflow correctness 規格索引與硬性原則 |
| `docs/architecture.md` | 補「時間 + 狀態雙重驅動」架構與 AI 閉環 |
| `docs/scheduler-plan.md` | 修正每日時序，明確 DataPrep 建 task、Notify 只讀結果 |
| `docs/api-spec.md` | 補 AI task API 前置狀態、錯誤碼、回應契約 |
| `docs/db-schema.md` | 補 migration 欄位與新 log table 建議 |
| `docs/claude-handoff.md` | 補 Claude file bridge 寫檔協定 |
| `docs/codex-handoff.md` | 補 Codex 僅處理 `CLAUDE_DONE`、不可越級 submit |
| `docs/ai-rules/dual-ai-workflow.md` | 補「Codex 最終決策必須基於 CODEX_DONE 或明確 fallback」 |

## 2. 新版流程圖

### 2.1 核心 AI 閉環

```text
Java DataPrep / Workflow
  -> create ai_task(PENDING)
  -> write Claude request context

Claude
  -> write *.tmp
  -> atomic rename to *.json

ClaudeSubmitWatcher
  -> read stable *.json
  -> validate schema + task state
  -> submitClaudeResult
  -> ai_task: CLAUDE_DONE
  -> move file to processed/

Codex runner
  -> query CLAUDE_DONE task
  -> read Claude result from DB
  -> fetch latest market / quotes / positions / candidates
  -> POST codex-result
  -> ai_task: CODEX_DONE

Java Decision / Notify job
  -> verify AI gating mode
  -> FULL_AI_READY: evaluate and notify
  -> PARTIAL_AI_READY / AI_NOT_READY: fallback with reason code
  -> final_decision references ai_task_id
  -> finalize task when applicable
```

### 2.2 每日時序

```text
08:10 PREMARKET DataPrep creates PREMARKET task
08:20 Claude submits PREMARKET
08:28 Codex submits PREMARKET review
08:30 Java PremarketNotify reads result or explicit fallback

09:01 OPENING DataPrep creates OPENING task
09:20 Claude submits OPENING
09:28 Codex submits OPENING review
09:30 Java FinalDecision reads OPENING first, then PREMARKET fallback if configured

10:40 MIDDAY DataPrep creates MIDDAY task
10:50 Claude submits MIDDAY
10:58 Codex submits MIDDAY review
11:00 Java MiddayReview reads result or explicit fallback

14:00 Java AftermarketReview is Codex/Java-only trade review and PnL review

15:05 POSTMARKET DataPrep creates POSTMARKET task
15:20 Claude submits POSTMARKET
15:28 Codex submits POSTMARKET review
15:30 Java PostmarketAnalysis reads result or explicit fallback; it must not create task

17:40 T86 DataPrep creates T86_TOMORROW task after T86 source is expected available
17:50 Claude submits T86_TOMORROW
17:58 Codex submits T86_TOMORROW review
18:00 or 18:30 Java TomorrowPlan reads result or explicit fallback
```

正式規格：**task 必須由 Java DataPrep / Workflow 建立；file bridge 不作為主要建 task 手段。**  
bridge 自動建 task 只允許開發模式或 emergency fallback，且必須寫入 `fallback_reason=TASK_AUTO_CREATED_BY_BRIDGE`。

## 3. AI task 狀態機定義

### 3.1 合法狀態

| 狀態 | 意義 |
|---|---|
| `PENDING` | Java 已建立 task，等待 Claude 認領或 file bridge submit |
| `CLAUDE_RUNNING` | Claude 已認領或 Java 判定 Claude 正在處理 |
| `CLAUDE_DONE` | Claude 結果與分數已成功寫入 DB |
| `CODEX_RUNNING` | Codex 已認領，正在補行情與審核 |
| `CODEX_DONE` | Codex 結果、分數、veto 已成功寫入 DB |
| `FINALIZED` | Java 已用該 task 完成正式通知或決策 |
| `FAILED` | task 因錯誤失敗，原 task 不可直接倒退重跑 |
| `EXPIRED` | task 超時過期，原 task 不可直接倒退重跑 |

### 3.2 合法轉移

```text
PENDING -> CLAUDE_RUNNING
CLAUDE_RUNNING -> CLAUDE_DONE
CLAUDE_DONE -> CODEX_RUNNING
CODEX_RUNNING -> CODEX_DONE
CODEX_DONE -> FINALIZED

PENDING -> FAILED / EXPIRED
CLAUDE_RUNNING -> FAILED / EXPIRED
CODEX_RUNNING -> FAILED / EXPIRED
CLAUDE_DONE -> EXPIRED
CODEX_DONE -> EXPIRED only before FINALIZED and only by explicit admin operation
```

### 3.3 明確禁止

```text
CODEX_DONE -> CLAUDE_DONE
CODEX_DONE -> CLAUDE_RUNNING
FINALIZED -> any previous state
FAILED -> RUNNING
EXPIRED -> RUNNING
CLAUDE_DONE -> PENDING
CODEX_DONE -> PENDING
```

若需要重跑，必須建立新 task 或將舊 task 標記 `EXPIRED` 後建立新 task。不得把舊 task 狀態倒退。

### 3.4 API 狀態前置條件

| Endpoint | 允許前置狀態 | 成功後狀態 | 非法狀態錯誤 |
|---|---|---|---|
| `POST /api/ai/tasks/{id}/claim-claude` | `PENDING` | `CLAUDE_RUNNING` | `409 AI_TASK_INVALID_STATE` |
| `POST /api/ai/tasks/{id}/claude-result` | `PENDING`, `CLAUDE_RUNNING` | `CLAUDE_DONE` | `409 AI_TASK_INVALID_STATE` |
| `POST /api/ai/tasks/{id}/claim-codex` | `CLAUDE_DONE` | `CODEX_RUNNING` | `409 AI_TASK_INVALID_STATE` |
| `POST /api/ai/tasks/{id}/codex-result` | `CLAUDE_DONE`, `CODEX_RUNNING` | `CODEX_DONE` | `409 AI_TASK_INVALID_STATE` |
| `POST /api/ai/tasks/{id}/finalize` | `CODEX_DONE` | `FINALIZED` | `409 AI_TASK_INVALID_STATE` |
| `POST /api/ai/tasks/{id}/fail` | `PENDING`, `CLAUDE_RUNNING`, `CODEX_RUNNING` | `FAILED` | `409 AI_TASK_INVALID_STATE` |
| `POST /api/ai/tasks/{id}/expire` | non-terminal states | `EXPIRED` | `409 AI_TASK_INVALID_STATE` |

### 3.5 Idempotency 與 optimistic lock

新增規格：

- `ai_task.version`：JPA optimistic lock 欄位。
- `ai_task.last_transition_at`：最後狀態轉移時間。
- `ai_task.last_transition_reason`：狀態轉移原因。
- `ai_task.result_hash` 或 provider-specific hash：避免相同檔案重複 submit。

Idempotency 規則：

- 相同 task、相同 provider、相同 `result_hash` 的重送可以回 `200 OK idempotent=true`。
- 相同 task、不同 hash 的重送若狀態已前進，回 `409 AI_TASK_ALREADY_ADVANCED`。
- `FINALIZED` 後一律不可覆蓋。

## 4. File bridge 協定

### 4.1 寫檔協定

Claude 必須遵守：

```text
write:  claude-submit/<taskType>-<tradingDate>-<HHmm>-<taskId>.tmp
rename: claude-submit/<taskType>-<tradingDate>-<HHmm>-<taskId>.json
```

Java watcher 只處理 `.json`，不得讀取 `.tmp`。

### 4.2 檔名格式

正式格式：

```text
claude-<TASK_TYPE>-<YYYY-MM-DD>-<HHmm>-task-<TASK_ID>.json
```

範例：

```text
claude-POSTMARKET-2026-04-20-1520-task-42.json
```

允許 fallback 格式：

```text
claude-<TASK_TYPE>-<YYYY-MM-DD>-<HHmm>.json
```

但若檔名缺 `taskId`，bridge 必須用 `taskType + tradingDate` 找最新未終止 task；找不到時不可默默新建正式 task，必須標 `TASK_NOT_FOUND`。

### 4.3 JSON schema

```json
{
  "taskId": 42,
  "taskType": "POSTMARKET",
  "tradingDate": "2026-04-20",
  "contentMarkdown": "Claude research markdown",
  "scores": {
    "3231": 8.5,
    "2303": 7.8
  },
  "thesis": {
    "3231": "基本面與籌碼面摘要"
  },
  "riskFlags": [
    "高檔爆量",
    "外資連賣"
  ],
  "generatedAt": "2026-04-20T15:20:00+08:00",
  "schemaVersion": "claude-submit-v2.1"
}
```

必要欄位：

- `taskType`
- `tradingDate`
- `contentMarkdown`
- `generatedAt`
- `schemaVersion`

強烈建議欄位：

- `taskId`
- `scores`
- `thesis`
- `riskFlags`

### 4.4 目錄規則

```text
claude-submit/
  pending *.tmp / *.json
  processed/
  failed/
  retry/
```

處理結果：

- 成功：移到 `processed/<originalName>.processed.json`
- schema 解析失敗：移到 `failed/<originalName>.failed.json`
- task 狀態非法：移到 `failed/<originalName>.failed.json`
- 可重試外部錯誤：移到 `retry/<originalName>.retry.json`

### 4.5 避免重複處理

bridge 必須：

1. 只處理副檔名 `.json`。
2. 檢查檔案 stable time，例如最後修改時間距今至少 2 秒。
3. 計算 checksum 或 hash，寫入 `file_bridge_error_log` 或 `ai_task` result hash。
4. 同一 hash 已 processed 時不重複 submit。
5. 處理前先嘗試 rename 成 `.processing` 或使用檔案鎖，避免兩個 watcher 同時處理。

失敗時必須：

- 記錄 DB。
- 寫 scheduler log。
- 發 LINE 系統通知，標示 `FILE_BRIDGE_PARSE_ERROR` 或 `AI_TASK_INVALID_STATE`。

## 5. FinalDecision gating 規則

### 5.1 Config 預設

```properties
final_decision.require_claude=true
final_decision.require_codex=true
final_decision.ai_downgrade_enabled=true
final_decision.partial_ai_mode=WATCH
```

`require_codex` 預設必須改為 `true`。

### 5.2 AI readiness mode

| Mode | 條件 | 行為 |
|---|---|---|
| `FULL_AI_READY` | Claude + Codex 都完成 | 可正常 FinalDecision |
| `PARTIAL_AI_READY` | Claude 完成、Codex 未完成 | 預設 `WATCH` 或 `REST`，不得產出正常 ENTER |
| `AI_NOT_READY` | Claude 未完成 | `REST` 或 `SKIP`，明確標記 `AI_NOT_READY` |

### 5.3 09:30 Job 規則

09:30 FinalDecision 取 task 優先順序：

1. `OPENING` task 若存在，必須優先使用。
2. 若 `OPENING` 不存在且 config 允許，可 fallback 到 `PREMARKET`。
3. 若 `require_codex=true` 且目標 task 未達 `CODEX_DONE`，不可輸出正常 `ENTER`。

預設行為：

```text
FULL_AI_READY       -> evaluate FinalDecision normally
PARTIAL_AI_READY    -> WATCH, fallback_reason=CODEX_MISSING
AI_NOT_READY        -> REST, fallback_reason=AI_NOT_READY
AI timeout exceeded -> REST, fallback_reason=AI_TIMEOUT
```

### 5.4 LINE 顯示規則

不可接受：

```text
今日可進場...
來源：Trading System
```

但實際 AI 未完成。

必須顯示：

```text
【09:30 今日操作】
結論：只可觀察

原因：
- Claude 已完成，但 Codex 尚未完成
- 本輪降級為 WATCH，不輸出正式進場標的

AI 狀態：PARTIAL_AI_READY
降級原因：CODEX_MISSING

來源：Trading System
```

### 5.5 final_decision 記錄欄位

建議新增：

- `final_decision.ai_task_id`
- `final_decision.ai_status`
- `final_decision.fallback_reason`
- `final_decision.source_task_type`
- `final_decision.codex_done_at`
- `final_decision.claude_done_at`

## 6. Fallback / timeout 規則

### 6.1 不允許的舊行為

- AI 未完成但通知看起來像完整決策。
- 系統默默 fallback 但 DB 沒有記錄。
- fallback 沒有 reason code。
- Claude/Codex late submit 覆蓋已完成決策。

### 6.2 Fallback reason code

| Code | 意義 |
|---|---|
| `AI_NOT_READY` | Claude 未完成 |
| `AI_TIMEOUT` | AI 等待逾時 |
| `CODEX_MISSING` | Claude 完成但 Codex 未完成 |
| `FILE_BRIDGE_PARSE_ERROR` | Claude submit JSON 解析失敗 |
| `AI_TASK_INVALID_STATE` | submit 時 task 狀態不合法 |
| `TASK_EXPIRED` | task 已過期 |
| `TASK_NOT_FOUND` | bridge submit 找不到對應 task |
| `TASK_AUTO_CREATED_BY_BRIDGE` | bridge emergency fallback 建 task |

### 6.3 Timeout sweep

建議 config：

```properties
ai.task.claude.timeout.minutes=20
ai.task.codex.timeout.minutes=10
ai.task.pending.timeout.minutes=30
ai.task.sweep.enabled=true
ai.task.sweep.interval.seconds=60
```

規則：

- `CLAUDE_RUNNING` 超過 `claude.timeout.minutes`：轉 `FAILED` 或 `EXPIRED`。
- `CODEX_RUNNING` 超過 `codex.timeout.minutes`：轉 `FAILED` 或 `EXPIRED`。
- `PENDING` 超過 `pending.timeout.minutes`：轉 `EXPIRED`。
- 正式固定時段決策若遇到 timeout，必須寫 `fallback_reason=AI_TIMEOUT`。
- 重跑必須建立新 task，不得把舊 task 從 `FAILED/EXPIRED` 改回 `RUNNING`。

### 6.4 Fallback 可追溯要求

所有 fallback 必須同步寫入：

- `final_decision.fallback_reason`
- `final_decision.ai_status`
- `scheduler_execution_log.message`
- `daily_orchestration_status.notes`
- LINE / dashboard 顯示文字

## 7. 驗收標準

### 7.1 狀態機

- `CODEX_DONE` task 收到 Claude result 時，API 回 `409 AI_TASK_ALREADY_ADVANCED`。
- `FINALIZED` task 收到 Claude/Codex/finalize 重複請求時，不得覆蓋結果。
- `FAILED` / `EXPIRED` 不可直接 claim 成 running。
- 相同 result hash 重送可 idempotent success，不新增研究 log。

### 7.2 File bridge

- Java watcher 不讀 `.tmp`。
- Claude 寫一半的檔案不會被 Java 處理。
- 壞檔會移到 `failed/`。
- 同一檔案重送不會重複寫入 task 或 stock evaluation。
- bridge 狀態錯誤會有 DB log 與 LINE 系統通知。

### 7.3 盤後時序

- 15:05 後 DB 必須存在 `POSTMARKET` task，狀態為 `PENDING` 或更後段。
- 15:30 `PostmarketAnalysis1530Job` 不得 create `POSTMARKET` task。
- 15:30 若未達 `CODEX_DONE`，通知必須明確顯示 fallback reason。
- `T86_TOMORROW` task 建立時間與 Claude/Codex/Java 通知時間一致，不能讓 Java 通知早於 Codex 應完成時間。

### 7.4 FinalDecision gating

- `require_codex=true` 且 Codex 未完成時，FinalDecision 不得產出正常 `ENTER`。
- `PARTIAL_AI_READY` 必須輸出 `WATCH` 或 `REST`，不得假裝完整 AI 決策。
- `AI_NOT_READY` 必須輸出 `REST` 或 `SKIP`。
- `final_decision` 必須記錄 `ai_status`、`fallback_reason`、`ai_task_id`。

### 7.5 可追溯性

- 從任一 `final_decision` 可查到使用的 `ai_task_id`。
- 從任一 `ai_task` 可查到 Claude/Codex 完成時間與狀態轉移時間。
- 從 orchestration / scheduler log 可看出哪一步 fallback。
- LINE 文案能看出是否為完整 AI 決策或降級通知。

## 8. Migration / config / API 變更摘要

### 8.1 DB / migration

建議新增欄位：

```sql
ALTER TABLE ai_task
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN last_transition_at DATETIME NULL,
  ADD COLUMN last_transition_reason VARCHAR(100) NULL,
  ADD COLUMN result_hash VARCHAR(128) NULL;

ALTER TABLE final_decision
  ADD COLUMN ai_task_id BIGINT NULL,
  ADD COLUMN ai_status VARCHAR(30) NULL,
  ADD COLUMN fallback_reason VARCHAR(60) NULL,
  ADD COLUMN source_task_type VARCHAR(30) NULL,
  ADD COLUMN claude_done_at DATETIME NULL,
  ADD COLUMN codex_done_at DATETIME NULL;
```

建議新增 table：

```sql
CREATE TABLE file_bridge_error_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  file_name VARCHAR(255) NOT NULL,
  task_id BIGINT NULL,
  task_type VARCHAR(30) NULL,
  trading_date DATE NULL,
  error_code VARCHAR(60) NOT NULL,
  error_message VARCHAR(1000) NULL,
  file_hash VARCHAR(128) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

### 8.2 Config

```properties
final_decision.require_claude=true
final_decision.require_codex=true
final_decision.ai_downgrade_enabled=true
final_decision.partial_ai_mode=WATCH

ai.task.claude.timeout.minutes=20
ai.task.codex.timeout.minutes=10
ai.task.pending.timeout.minutes=30
ai.task.sweep.enabled=true
ai.task.sweep.interval.seconds=60

ai.file_bridge.use_tmp_protocol=true
ai.file_bridge.required_stable_seconds=2
ai.file_bridge.processed_dir=D:/ai/stock/claude-submit/processed
ai.file_bridge.failed_dir=D:/ai/stock/claude-submit/failed
ai.file_bridge.retry_dir=D:/ai/stock/claude-submit/retry
ai.file_bridge.allow_auto_create_task=false
```

### 8.3 API

新增或調整：

```http
POST /api/ai/tasks/{id}/claim-claude
POST /api/ai/tasks/{id}/claude-result
POST /api/ai/tasks/{id}/claim-codex
POST /api/ai/tasks/{id}/codex-result
POST /api/ai/tasks/{id}/finalize
POST /api/ai/tasks/{id}/fail
POST /api/ai/tasks/{id}/expire
GET  /api/ai/tasks/{id}/transitions
```

錯誤 response：

```json
{
  "success": false,
  "errorCode": "AI_TASK_INVALID_STATE",
  "message": "Cannot submit Claude result when task status is CODEX_DONE",
  "taskId": 42,
  "currentStatus": "CODEX_DONE",
  "expectedStatuses": ["PENDING", "CLAUDE_RUNNING"]
}
```

成功 idempotent response：

```json
{
  "success": true,
  "idempotent": true,
  "taskId": 42,
  "status": "CLAUDE_DONE"
}
```

