-- V8: Workflow Correctness & AI Orchestration 一致性（v2.1）
-- 對應 docs/workflow-correctness-ai-orchestration-spec.md

-- ── 1. ai_task 新增欄位：version / last_transition / result_hash ────────────
ALTER TABLE ai_task
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_transition_at DATETIME NULL,
    ADD COLUMN last_transition_reason VARCHAR(100) NULL,
    ADD COLUMN result_hash VARCHAR(128) NULL;

-- ── 2. final_decision 新增 AI 追溯欄位 ──────────────────────────────────────
ALTER TABLE final_decision
    ADD COLUMN ai_task_id BIGINT NULL,
    ADD COLUMN ai_status VARCHAR(30) NULL,
    ADD COLUMN fallback_reason VARCHAR(60) NULL,
    ADD COLUMN source_task_type VARCHAR(30) NULL,
    ADD COLUMN claude_done_at DATETIME NULL,
    ADD COLUMN codex_done_at DATETIME NULL;

CREATE INDEX idx_final_decision_ai_task ON final_decision(ai_task_id);

-- ── 3. file_bridge_error_log：Claude submit file bridge 錯誤稽核 ─────────────
CREATE TABLE IF NOT EXISTS file_bridge_error_log (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    file_name      VARCHAR(255) NOT NULL,
    task_id        BIGINT NULL,
    task_type      VARCHAR(30) NULL,
    trading_date   DATE NULL,
    error_code     VARCHAR(60) NOT NULL,
    error_message  VARCHAR(1000) NULL,
    file_hash      VARCHAR(128) NULL,
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_fbel_trading_date (trading_date),
    INDEX idx_fbel_error_code (error_code),
    INDEX idx_fbel_task_id (task_id)
);

-- ── 4. v2.1 預設 score_config：require_codex=true、partial_ai_mode=WATCH ─────
-- INSERT IGNORE 讓已有 key 不被覆蓋（允許 Austin 手動調成 false）
INSERT IGNORE INTO score_config (config_key, config_value, value_type, description) VALUES
('final_decision.require_claude',       'true',  'BOOLEAN', 'FinalDecision 需要 Claude 完成（v2.1）'),
('final_decision.require_codex',        'true',  'BOOLEAN', 'FinalDecision 需要 Codex 完成（v2.1 新預設）'),
('final_decision.ai_downgrade_enabled', 'true',  'BOOLEAN', 'AI 未完成時允許降級（v2.1）'),
('final_decision.partial_ai_mode',      'WATCH', 'STRING',  'Claude 完成、Codex 未完成時行為：WATCH 或 REST'),
('ai.task.claude.timeout.minutes',      '20',    'INTEGER', 'CLAUDE_RUNNING 超時 → FAILED'),
('ai.task.codex.timeout.minutes',       '10',    'INTEGER', 'CODEX_RUNNING 超時 → FAILED'),
('ai.task.pending.timeout.minutes',     '30',    'INTEGER', 'PENDING 超時 → EXPIRED');
