-- P1 feedback-loop data validation: persist candidate forward max drawdown.
-- Guarded so reruns do not fail.

DROP PROCEDURE IF EXISTS p_v34_apply;
DELIMITER //
CREATE PROCEDURE p_v34_apply()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'candidate_forward_tracking'
                     AND column_name = 'max_drawdown_pct') THEN
        ALTER TABLE candidate_forward_tracking ADD COLUMN max_drawdown_pct DECIMAL(12,4) NULL;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'candidate_forward_tracking'
                     AND index_name = 'idx_candidate_forward_key') THEN
        ALTER TABLE candidate_forward_tracking
            ADD INDEX idx_candidate_forward_key (trading_date, stock_id, final_decision);
    END IF;
END //
DELIMITER ;
CALL p_v34_apply();
DROP PROCEDURE p_v34_apply;
