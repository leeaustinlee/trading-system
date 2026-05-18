-- W1-1 Decision Snapshot Ledger (shadow / side-effect-only).
-- Stores an immutable read-only snapshot around each persisted final_decision for audit/replay.

CREATE TABLE IF NOT EXISTS decision_snapshot_ledger (
    id BIGINT NOT NULL AUTO_INCREMENT,
    final_decision_id BIGINT NOT NULL,
    trading_date DATE NOT NULL,
    source_task_type VARCHAR(30) NULL,
    prefer_task_type VARCHAR(30) NULL,
    ai_task_id BIGINT NULL,
    ai_status VARCHAR(30) NULL,
    ai_readiness_mode VARCHAR(30) NULL,
    fallback_reason VARCHAR(100) NULL,
    final_decision_code VARCHAR(30) NULL,
    selected_symbols_json JSON NULL,
    rejected_symbols_json JSON NULL,
    watch_symbols_json JSON NULL,
    merged_symbols_json JSON NULL,
    candidate_universe_json JSON NULL,
    candidate_scores_json JSON NULL,
    market_context_json JSON NULL,
    gate_trace_json JSON NULL,
    decision_trace_json JSON NULL,
    response_payload_json JSON NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_decision_snapshot_ledger_final_decision (final_decision_id),
    KEY idx_decision_snapshot_ledger_trading_date (trading_date),
    KEY idx_decision_snapshot_ledger_created_at (created_at),
    CONSTRAINT fk_decision_snapshot_ledger_final_decision
        FOREIGN KEY (final_decision_id) REFERENCES final_decision(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
