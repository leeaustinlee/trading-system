-- P4 RR shadow validation expanded sample source fields.
-- Guarded for idempotent manual migration runs.

DROP PROCEDURE IF EXISTS p_v37_apply;
DELIMITER //
CREATE PROCEDURE p_v37_apply()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'rr_shadow_validation'
                     AND column_name = 'source_type') THEN
        ALTER TABLE rr_shadow_validation ADD COLUMN source_type VARCHAR(30) NOT NULL DEFAULT 'PAPER_TRADE' AFTER id;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'rr_shadow_validation'
                     AND column_name = 'source_forward_tracking_id') THEN
        ALTER TABLE rr_shadow_validation ADD COLUMN source_forward_tracking_id BIGINT NULL AFTER paper_trade_id;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'rr_shadow_validation'
                     AND column_name = 'final_decision') THEN
        ALTER TABLE rr_shadow_validation ADD COLUMN final_decision VARCHAR(50) NULL AFTER root_cause_bucket;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'rr_shadow_validation'
                     AND column_name = 'final_score') THEN
        ALTER TABLE rr_shadow_validation ADD COLUMN final_score DECIMAL(12,4) NULL AFTER final_decision;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'rr_shadow_validation'
                     AND column_name = 'grade') THEN
        ALTER TABLE rr_shadow_validation ADD COLUMN grade VARCHAR(20) NULL AFTER final_score;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'rr_shadow_validation'
                     AND column_name = 'theme_tag') THEN
        ALTER TABLE rr_shadow_validation ADD COLUMN theme_tag VARCHAR(100) NULL AFTER grade;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'rr_shadow_validation'
                     AND column_name = 'gate_name') THEN
        ALTER TABLE rr_shadow_validation ADD COLUMN gate_name VARCHAR(80) NULL AFTER theme_tag;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'rr_shadow_validation'
                     AND column_name = 'validation_note') THEN
        ALTER TABLE rr_shadow_validation ADD COLUMN validation_note VARCHAR(1000) NULL AFTER gate_name;
    END IF;

    UPDATE rr_shadow_validation
       SET source_type = 'PAPER_TRADE'
     WHERE source_type IS NULL OR source_type = '';

    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'rr_shadow_validation'
                     AND index_name = 'idx_rr_shadow_validation_source_type') THEN
        ALTER TABLE rr_shadow_validation ADD INDEX idx_rr_shadow_validation_source_type (source_type);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'rr_shadow_validation'
                     AND index_name = 'idx_rr_shadow_validation_strategy') THEN
        ALTER TABLE rr_shadow_validation ADD INDEX idx_rr_shadow_validation_strategy (strategy_type);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'rr_shadow_validation'
                     AND index_name = 'idx_rr_shadow_validation_theme') THEN
        ALTER TABLE rr_shadow_validation ADD INDEX idx_rr_shadow_validation_theme (theme_tag);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'rr_shadow_validation'
                     AND index_name = 'idx_rr_shadow_validation_decision') THEN
        ALTER TABLE rr_shadow_validation ADD INDEX idx_rr_shadow_validation_decision (final_decision);
    END IF;
END //
DELIMITER ;
CALL p_v37_apply();
DROP PROCEDURE p_v37_apply;
