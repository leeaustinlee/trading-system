-- Phase 1.0 Structure-aware Exit Arbiter Shadow Logging
-- Shadow-only schema: records arbiter decisions and post-exit washout outcomes.
-- No existing data is deleted or rewritten.

CREATE TABLE IF NOT EXISTS structural_exit_decision_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trade_ref_type VARCHAR(20) NOT NULL,
    trade_ref_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    evaluated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    evaluation_date DATE NULL,
    source_review_log_id BIGINT NULL,
    review_date DATE NULL,
    mode VARCHAR(20) NOT NULL DEFAULT 'LIVE',

    source_decision_status VARCHAR(40) NULL,
    source_exit_reason VARCHAR(512) NULL,

    arbiter_tier VARCHAR(40) NOT NULL,
    arbiter_reason VARCHAR(512) NULL,
    risk_block TINYINT(1) NOT NULL DEFAULT 0,
    manual_confirm_required TINYINT(1) NOT NULL DEFAULT 1,
    auto_sell_enabled TINYINT(1) NOT NULL DEFAULT 0,

    theme_state VARCHAR(40) NULL,
    theme_stage VARCHAR(40) NULL,
    theme_rank INT NULL,
    theme_score DECIMAL(8,4) NULL,
    mainstream_theme TINYINT(1) NULL,

    structure_state VARCHAR(40) NULL,
    health_score INT NULL,
    structure_status VARCHAR(80) NULL,
    volume_status VARCHAR(80) NULL,
    relative_strength_status VARCHAR(80) NULL,
    chip_status VARCHAR(80) NULL,

    price_state VARCHAR(40) NULL,
    current_price DECIMAL(12,4) NULL,
    entry_price DECIMAL(12,4) NULL,
    hard_stop_price DECIMAL(12,4) NULL,
    trailing_stop_price DECIMAL(12,4) NULL,
    dynamic_stop_price DECIMAL(12,4) NULL,
    ma5 DECIMAL(12,4) NULL,
    ma10 DECIMAL(12,4) NULL,
    ma20 DECIMAL(12,4) NULL,
    previous_low DECIMAL(12,4) NULL,
    recent_high DECIMAL(12,4) NULL,
    atr DECIMAL(12,4) NULL,

    price_trigger_json JSON NULL,
    layer_votes_json JSON NULL,
    data_gaps_json JSON NULL,
    reason_json JSON NULL,
    audit_tags_json JSON NULL,

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    KEY idx_struct_exit_ref (trade_ref_type, trade_ref_id),
    KEY idx_struct_exit_symbol_date (symbol, evaluation_date),
    KEY idx_struct_exit_review (source_review_log_id, mode),
    KEY idx_struct_exit_mode_date (mode, evaluation_date),
    KEY idx_struct_exit_tier_date (arbiter_tier, evaluated_at),
    KEY idx_struct_exit_source_date (source_decision_status, evaluated_at),
    KEY idx_struct_exit_price_state (price_state),
    KEY idx_struct_exit_theme_structure (theme_state, structure_state)
);

CREATE TABLE IF NOT EXISTS stop_washout_outcome (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    structural_exit_log_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    exit_signal_at DATETIME NOT NULL,
    signal_tier VARCHAR(40) NULL,
    outcome_basis VARCHAR(40) NOT NULL DEFAULT 'SOURCE_EXIT',
    source_decision_status VARCHAR(40) NULL,
    signal_price DECIMAL(12,4) NULL,

    t1_max_return_pct DECIMAL(10,4) NULL,
    t3_max_return_pct DECIMAL(10,4) NULL,
    t5_max_return_pct DECIMAL(10,4) NULL,
    t10_max_return_pct DECIMAL(10,4) NULL,
    high_1d DECIMAL(12,4) NULL,
    high_3d DECIMAL(12,4) NULL,
    high_5d DECIMAL(12,4) NULL,
    high_10d DECIMAL(12,4) NULL,
    new_high_3_10d TINYINT(1) NULL,
    outcome_label VARCHAR(40) NULL,

    evaluated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_washout_struct_log_basis (structural_exit_log_id, outcome_basis),
    KEY idx_washout_struct_log (structural_exit_log_id),
    KEY idx_washout_symbol_signal (symbol, exit_signal_at),
    KEY idx_washout_basis (outcome_basis),
    KEY idx_washout_label (outcome_label),
    KEY idx_washout_tier_label (signal_tier, outcome_label),
    KEY idx_washout_basis_tier_label (outcome_basis, signal_tier, outcome_label)
);

ALTER TABLE shadow_exit_comparison
    ADD COLUMN structural_exit_log_id BIGINT NULL,
    ADD COLUMN arbiter_tier VARCHAR(40) NULL,
    ADD COLUMN arbiter_reason VARCHAR(512) NULL,
    ADD COLUMN theme_state VARCHAR(40) NULL,
    ADD COLUMN structure_state VARCHAR(40) NULL,
    ADD COLUMN price_state VARCHAR(40) NULL,
    ADD COLUMN risk_block TINYINT(1) NULL,
    ADD COLUMN layer_votes_json JSON NULL;

CREATE INDEX idx_shadow_exit_struct_log ON shadow_exit_comparison (structural_exit_log_id);
CREATE INDEX idx_shadow_exit_arbiter_tier ON shadow_exit_comparison (arbiter_tier);
