-- W1-2 Notification Delivery Truth.
-- Idempotent ADD COLUMN guards allow local recovery/rerun without duplicate-column failures.

SET @schema_name := DATABASE();

SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'notification_log' AND column_name = 'provider'),
    'SELECT 1',
    'ALTER TABLE notification_log ADD COLUMN provider VARCHAR(30) NULL AFTER payload_json'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'notification_log' AND column_name = 'delivery_status'),
    'SELECT 1',
    "ALTER TABLE notification_log ADD COLUMN delivery_status VARCHAR(30) NULL DEFAULT 'CREATED' AFTER provider"
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'notification_log' AND column_name = 'attempted'),
    'SELECT 1',
    'ALTER TABLE notification_log ADD COLUMN attempted BOOLEAN NULL DEFAULT FALSE AFTER delivery_status'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'notification_log' AND column_name = 'delivered'),
    'SELECT 1',
    'ALTER TABLE notification_log ADD COLUMN delivered BOOLEAN NULL DEFAULT FALSE AFTER attempted'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'notification_log' AND column_name = 'attempted_at'),
    'SELECT 1',
    'ALTER TABLE notification_log ADD COLUMN attempted_at DATETIME NULL AFTER delivered'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'notification_log' AND column_name = 'delivered_at'),
    'SELECT 1',
    'ALTER TABLE notification_log ADD COLUMN delivered_at DATETIME NULL AFTER attempted_at'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'notification_log' AND column_name = 'provider_http_status'),
    'SELECT 1',
    'ALTER TABLE notification_log ADD COLUMN provider_http_status INT NULL AFTER delivered_at'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'notification_log' AND column_name = 'provider_message_id'),
    'SELECT 1',
    'ALTER TABLE notification_log ADD COLUMN provider_message_id VARCHAR(120) NULL AFTER provider_http_status'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'notification_log' AND column_name = 'error_code'),
    'SELECT 1',
    'ALTER TABLE notification_log ADD COLUMN error_code VARCHAR(80) NULL AFTER provider_message_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'notification_log' AND column_name = 'error_body'),
    'SELECT 1',
    'ALTER TABLE notification_log ADD COLUMN error_body TEXT NULL AFTER error_code'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'notification_log' AND column_name = 'retry_count'),
    'SELECT 1',
    'ALTER TABLE notification_log ADD COLUMN retry_count INT NULL DEFAULT 0 AFTER error_body'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE notification_log
   SET delivery_status = 'CREATED'
 WHERE delivery_status IS NULL OR delivery_status = '';

UPDATE notification_log
   SET attempted = FALSE
 WHERE attempted IS NULL;

UPDATE notification_log
   SET delivered = FALSE
 WHERE delivered IS NULL;

UPDATE notification_log
   SET retry_count = 0
 WHERE retry_count IS NULL;
