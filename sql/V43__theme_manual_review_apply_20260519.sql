-- W2-16 Theme taxonomy manual review apply.
-- Applies the reviewed evidence-backed decisions from:
-- docs/trading-upgrade/theme-manual-review-decisions-2026-05-19.csv
--
-- Data-quality only: updates theme_category/source/confidence for the reviewed
-- unresolved OTHER queue so taxonomy observability can move from MANUAL_REVIEW
-- to OK. Does not alter candidate ranking, BUY/SELL/FinalDecision semantics,
-- risk gates, price gates, capital sizing, or live order behavior.
--
-- Guards:
-- - symbol + theme_tag scoped to the reviewed worksheet rows
-- - only active rows still categorized as OTHER/blank are updated
-- - diagnostic sentinels UNRESOLVED_OTHER / UNKNOWN are never written

UPDATE stock_theme_mapping
   SET theme_category = CASE symbol
       WHEN '2481' THEN 'SEMICONDUCTOR'
       WHEN '3042' THEN 'ELECTRONICS_COMPONENTS'
       WHEN '3305' THEN 'MATERIALS'
       WHEN '3605' THEN 'ELECTRONICS_COMPONENTS'
       ELSE theme_category
   END,
       source = 'manual-review-20260519',
       confidence = CASE
           WHEN confidence IS NULL OR confidence < 0.95 THEN 0.95
           ELSE confidence
       END
 WHERE symbol IN ('2481', '3042', '3305', '3605')
   AND theme_tag = '其他強勢股'
   AND COALESCE(is_active, 1) = 1
   AND (theme_category IS NULL OR TRIM(theme_category) = '' OR theme_category = 'OTHER');
