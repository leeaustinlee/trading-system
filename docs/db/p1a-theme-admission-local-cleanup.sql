-- P1-A Theme Admission local cleanup helper
-- Scope: local/staging verification only. Do not run in production without review.
-- Purpose: remove only rows created by guarded Theme Admission P1-A verification.
-- Safety pattern: preview -> delete in transaction -> verify -> COMMIT or ROLLBACK.

USE trading_system;

START TRANSACTION;

-- 1) Preview candidate rows created by P1-A theme admission.
SELECT
    'candidate_stock' AS table_name,
    id,
    trading_date,
    symbol,
    stock_name,
    theme_tag,
    JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.admission_type')) AS admission_type,
    JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.source_signal_id')) AS source_signal_id
FROM candidate_stock
WHERE payload_json IS NOT NULL
  AND JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.source')) = 'THEME_ADMISSION'
ORDER BY trading_date, symbol, id;

-- 2) Preview watchlist rows created by P1-A theme admission.
SELECT
    'watchlist_stock' AS table_name,
    id,
    symbol,
    stock_name,
    theme_tag,
    first_seen_date,
    last_seen_date,
    source_type,
    JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.admission_type')) AS admission_type,
    JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.source_signal_id')) AS source_signal_id
FROM watchlist_stock
WHERE payload_json IS NOT NULL
  AND JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.source')) = 'THEME_ADMISSION'
ORDER BY first_seen_date, symbol, id;

-- 3) Delete only P1-A theme admission rows.
DELETE FROM candidate_stock
WHERE payload_json IS NOT NULL
  AND JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.source')) = 'THEME_ADMISSION';

DELETE FROM watchlist_stock
WHERE payload_json IS NOT NULL
  AND JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.source')) = 'THEME_ADMISSION';

-- 4) Verify cleanup result before COMMIT.
SELECT COUNT(*) AS remaining_candidate_theme_admission
FROM candidate_stock
WHERE payload_json IS NOT NULL
  AND JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.source')) = 'THEME_ADMISSION';

SELECT COUNT(*) AS remaining_watchlist_theme_admission
FROM watchlist_stock
WHERE payload_json IS NOT NULL
  AND JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.source')) = 'THEME_ADMISSION';

-- Choose exactly one after reviewing preview + verify output:
-- COMMIT;
-- ROLLBACK;
