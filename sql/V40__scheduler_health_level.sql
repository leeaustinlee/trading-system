-- W1-3 Scheduler Health Level.
-- Keep legacy status for compatibility, add a truth-level classification for data/output quality.
-- Deliberately do not add health_level with a DEFAULT: older rows must be backfilled
-- from status/message instead of being silently labeled SUCCESS_REAL.

SET @schema_name := DATABASE();

SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'scheduler_execution_log' AND column_name = 'health_level'),
    'SELECT 1',
    'ALTER TABLE scheduler_execution_log ADD COLUMN health_level VARCHAR(40) NULL AFTER status'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'scheduler_execution_log' AND column_name = 'health_reason'),
    'SELECT 1',
    'ALTER TABLE scheduler_execution_log ADD COLUMN health_reason VARCHAR(500) NULL AFTER health_level'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE scheduler_execution_log
   SET health_level = CASE
       WHEN status = 'FAILED' THEN 'FAILED'
       WHEN LOWER(COALESCE(message, '')) REGEXP 'fallback|fallback_used' THEN 'SUCCESS_WITH_FALLBACK'
       WHEN LOWER(COALESCE(message, '')) REGEXP 'degraded|incomplete|stale|failures=[1-9]' THEN 'DEGRADED'
       WHEN LOWER(COALESCE(message, '')) REGEXP 'no data|no market snapshot|no candidates|empty|upserted=0' THEN 'EMPTY_DATA'
       WHEN LOWER(COALESCE(message, '')) REGEXP 'skip|skipped' THEN 'SKIPPED'
       ELSE 'SUCCESS_REAL'
   END
 WHERE health_reason IS NULL OR health_level IS NULL OR health_level = '';

UPDATE scheduler_execution_log
   SET health_reason = message
 WHERE health_reason IS NULL AND message IS NOT NULL;

UPDATE scheduler_execution_log
   SET health_level = 'SUCCESS_REAL'
 WHERE health_level IS NULL OR health_level = '';
