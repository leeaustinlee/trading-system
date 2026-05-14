-- Trading Recovery Feedback Loop (shadow-only)
--
-- Purpose:
-- 1) Store price-plan sanity flags on paper_trade.
-- 2) Persist shadow position health diagnostics.
-- 3) Persist shadow exit-rule comparisons.
--
-- Safety:
-- - ADD COLUMN / ADD INDEX are guarded with information_schema checks.
-- - No existing data is deleted or mutated.
-- - All config defaults keep the new rules in shadow-only mode.

DROP PROCEDURE IF EXISTS p_v33_apply;
DELIMITER //
CREATE PROCEDURE p_v33_apply()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'paper_trade'
                     AND column_name = 'intended_exit_price') THEN
        ALTER TABLE paper_trade ADD COLUMN intended_exit_price DECIMAL(12,4) NULL;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'paper_trade'
                     AND column_name = 'sanity_result') THEN
        ALTER TABLE paper_trade ADD COLUMN sanity_result VARCHAR(30) NULL;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'paper_trade'
                     AND column_name = 'sanity_violations') THEN
        ALTER TABLE paper_trade ADD COLUMN sanity_violations JSON NULL;
    END IF;

    CREATE TABLE IF NOT EXISTS position_health_log (
        id BIGINT PRIMARY KEY AUTO_INCREMENT,
        position_id BIGINT NOT NULL,
        symbol VARCHAR(20) NOT NULL,
        evaluated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        current_price DECIMAL(12,4) NULL,
        health_score INT NULL,
        structure_status VARCHAR(40) NULL,
        volume_status VARCHAR(40) NULL,
        relative_strength_status VARCHAR(40) NULL,
        chip_status VARCHAR(40) NULL,
        exit_tier VARCHAR(40) NULL,
        reasons_json JSON NULL,
        data_gaps_json JSON NULL
    );

    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'position_health_log'
                     AND index_name = 'idx_position_health_position') THEN
        ALTER TABLE position_health_log ADD INDEX idx_position_health_position (position_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'position_health_log'
                     AND index_name = 'idx_position_health_symbol') THEN
        ALTER TABLE position_health_log ADD INDEX idx_position_health_symbol (symbol);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'position_health_log'
                     AND index_name = 'idx_position_health_evaluated') THEN
        ALTER TABLE position_health_log ADD INDEX idx_position_health_evaluated (evaluated_at);
    END IF;

    CREATE TABLE IF NOT EXISTS shadow_exit_comparison (
        id BIGINT PRIMARY KEY AUTO_INCREMENT,
        trade_ref_type VARCHAR(20) NOT NULL,
        trade_ref_id BIGINT NOT NULL,
        symbol VARCHAR(20) NOT NULL,
        evaluated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        current_rule_action VARCHAR(40) NULL,
        current_rule_exit_price DECIMAL(12,4) NULL,
        ma5_action VARCHAR(40) NULL,
        ma5_price DECIMAL(12,4) NULL,
        ma10_action VARCHAR(40) NULL,
        ma10_price DECIMAL(12,4) NULL,
        prev_low_action VARCHAR(40) NULL,
        prev_low_price DECIMAL(12,4) NULL,
        atr_action VARCHAR(40) NULL,
        atr_price DECIMAL(12,4) NULL,
        hybrid_action VARCHAR(40) NULL,
        hybrid_price DECIMAL(12,4) NULL,
        hypothetical_return_json JSON NULL,
        data_gaps JSON NULL
    );

    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'shadow_exit_comparison'
                     AND index_name = 'idx_shadow_exit_ref') THEN
        ALTER TABLE shadow_exit_comparison ADD INDEX idx_shadow_exit_ref (trade_ref_type, trade_ref_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'shadow_exit_comparison'
                     AND index_name = 'idx_shadow_exit_symbol') THEN
        ALTER TABLE shadow_exit_comparison ADD INDEX idx_shadow_exit_symbol (symbol);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'shadow_exit_comparison'
                     AND index_name = 'idx_shadow_exit_evaluated') THEN
        ALTER TABLE shadow_exit_comparison ADD INDEX idx_shadow_exit_evaluated (evaluated_at);
    END IF;

    INSERT IGNORE INTO score_config (config_key, config_value, value_type, description) VALUES
    ('price_plan.sanity.enabled', 'true', 'BOOLEAN', 'Enable price plan sanity evaluator in shadow/diagnosis mode'),
    ('price_plan.sanity.shadow_only', 'true', 'BOOLEAN', 'Do not block production BUY; only record sanity flags'),
    ('price_plan.min_rr.setup', '1.8', 'DECIMAL', 'Minimum RR for setup price plan sanity'),
    ('price_plan.min_rr.momentum', '2.0', 'DECIMAL', 'Minimum RR for momentum price plan sanity'),
    ('price_plan.tp1_min_gain_pct', '3.0', 'DECIMAL', 'Minimum TP1 gain percentage over entry'),
    ('price_plan.stop_min_loss_pct', '1.5', 'DECIMAL', 'Minimum stop distance percentage below entry'),
    ('price_plan.stop_max_loss_pct', '10.0', 'DECIMAL', 'Maximum stop distance percentage below entry');
END //
DELIMITER ;
CALL p_v33_apply();
DROP PROCEDURE p_v33_apply;
