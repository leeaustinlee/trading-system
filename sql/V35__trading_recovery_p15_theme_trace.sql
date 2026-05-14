-- P1.5 recovery feedback loop: candidate forward theme trace repair fields.
-- Guarded for idempotent manual migration runs.

DROP PROCEDURE IF EXISTS p_v35_apply;
DELIMITER //
CREATE PROCEDURE p_v35_apply()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'candidate_forward_tracking'
                     AND column_name = 'theme_tag') THEN
        ALTER TABLE candidate_forward_tracking ADD COLUMN theme_tag VARCHAR(100) NULL;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'candidate_forward_tracking'
                     AND column_name = 'theme_reason') THEN
        ALTER TABLE candidate_forward_tracking ADD COLUMN theme_reason VARCHAR(1000) NULL;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'candidate_forward_tracking'
                     AND column_name = 'source_candidate_id') THEN
        ALTER TABLE candidate_forward_tracking ADD COLUMN source_candidate_id BIGINT NULL;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'candidate_forward_tracking'
                     AND index_name = 'idx_candidate_forward_theme') THEN
        ALTER TABLE candidate_forward_tracking ADD INDEX idx_candidate_forward_theme (theme_tag);
    END IF;
END //
DELIMITER ;
CALL p_v35_apply();
DROP PROCEDURE p_v35_apply;
