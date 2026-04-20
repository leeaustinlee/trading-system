# DB Schema (v1)

## DB
- MySQL 8+
- Charset: utf8mb4
- 時區：建議 `Asia/Taipei`

## 核心表
- market_snapshot
- sector_snapshot
- candidate_stock
- stock_evaluation
- final_decision
- trading_plan
- hourly_gate_decision
- monitor_decision
- position
- daily_pnl
- notification_log
- trading_state
- scheduler_execution_log

細節見 `sql/V1__init_schema.sql`。
## v2.1 Workflow Correctness migration 規格

最新完整規格見 `docs/workflow-correctness-ai-orchestration-spec.md`。

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
