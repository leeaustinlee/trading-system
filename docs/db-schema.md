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
