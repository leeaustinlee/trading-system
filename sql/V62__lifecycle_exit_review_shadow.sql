-- P3-C Lifecycle Exit Review Hook.
-- Shadow/advisory table only. This migration does not wire lifecycle review into
-- BUY/SELL/final decision/execution, stop mutation, position mutation, or schedulers.

CREATE TABLE IF NOT EXISTS lifecycle_exit_review_shadow (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    review_date DATE NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    stock_name VARCHAR(120) NULL,
    position_id BIGINT NULL,
    theme_tag VARCHAR(100) NULL,
    lifecycle_stage VARCHAR(40) NULL,
    previous_stage VARCHAR(40) NULL,
    lifecycle_score DECIMAL(8,4) NULL,
    continuation_days INT NULL,
    breadth INT NULL,
    leader_count INT NULL,
    rotation_score DECIMAL(8,4) NULL,
    crowding_score DECIMAL(8,4) NULL,
    review_action VARCHAR(60) NOT NULL,
    review_priority VARCHAR(30) NOT NULL,
    review_only TINYINT(1) NOT NULL DEFAULT 1,
    auto_sell_enabled TINYINT(1) NOT NULL DEFAULT 0,
    stop_mutation_enabled TINYINT(1) NOT NULL DEFAULT 0,
    position_mutation_enabled TINYINT(1) NOT NULL DEFAULT 0,
    source_position_status VARCHAR(40) NULL,
    structural_exit_tier VARCHAR(40) NULL,
    price_state VARCHAR(40) NULL,
    structure_state VARCHAR(40) NULL,
    data_gap_reason VARCHAR(500) NULL,
    reason_json JSON NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_lifecycle_exit_review_date_symbol (review_date, symbol),
    KEY idx_lifecycle_exit_review_date_priority (review_date, review_priority),
    KEY idx_lifecycle_exit_review_action_date (review_action, review_date),
    KEY idx_lifecycle_exit_review_theme_date (theme_tag, review_date),
    KEY idx_lifecycle_exit_review_position (position_id),
    KEY idx_lifecycle_exit_review_stage_date (lifecycle_stage, review_date)
);
