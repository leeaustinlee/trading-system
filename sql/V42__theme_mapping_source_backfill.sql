-- W2-3 Theme mapping source provenance backfill.
-- Data-quality only: fills blank mapping source labels so observability can
-- distinguish legacy/import provenance from truly unknown source gaps.
-- Does not touch BUY/SELL/FinalDecision/scoring/risk/capital columns.

UPDATE stock_theme_mapping
SET source = CASE
    WHEN UPPER(TRIM(theme_tag)) LIKE 'AI_CHIP%' THEN 'legacy-ai-chip-seed'
    ELSE 'legacy-theme-mapping'
END
WHERE source IS NULL OR TRIM(source) = '';
