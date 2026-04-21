-- ── V12: P0.2 Ranking Layer ───────────────────────────────────────────────────
-- stock_ranking_snapshot  — per-candidate ranking output from StockRankingEngine.
-- Note: ddl-auto=update creates this on first startup; this file is for DR / fresh-env.

CREATE TABLE IF NOT EXISTS stock_ranking_snapshot (
    id                      BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trading_date            DATE            NOT NULL,
    symbol                  VARCHAR(20)     NOT NULL,
    selection_score         DECIMAL(7,3)    NULL     COMMENT 'Weighted composite score [0,10]',
    relative_strength_score DECIMAL(7,3)    NULL     COMMENT 'RS proxy (P0.2: baseScore norm); real value from P1',
    theme_strength_score    DECIMAL(7,3)    NULL     COMMENT 'Theme proxy (P0.2: finalThemeScore); real value from P1.1',
    thesis_score            DECIMAL(7,3)    NULL     COMMENT 'Claude/Codex thesis contribution [0,10]',
    theme_tag               VARCHAR(60)     NULL,
    vetoed                  TINYINT(1)      NOT NULL DEFAULT 0,
    eligible_for_setup      TINYINT(1)      NOT NULL DEFAULT 0,
    rejection_reason        VARCHAR(255)    NULL,
    score_breakdown_json    JSON            NULL,
    created_at              TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_ranking_date_score  (trading_date, selection_score DESC),
    INDEX idx_ranking_date_symbol (trading_date, symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── score_config seeds for ranking weights (INSERT IGNORE = idempotent) ───────
INSERT IGNORE INTO score_config (config_key, config_value, value_type, description) VALUES
('ranking.weight.rs',              '0.30', 'DECIMAL', 'selectionScore: relative-strength weight'),
('ranking.weight.theme',           '0.25', 'DECIMAL', 'selectionScore: theme-strength weight'),
('ranking.weight.java_structure',  '0.25', 'DECIMAL', 'selectionScore: java structural score weight'),
('ranking.weight.thesis',          '0.20', 'DECIMAL', 'selectionScore: Claude/Codex thesis weight'),
('ranking.top_n',                  '3',    'INTEGER', 'Max candidates that pass eligibleForSetup=true'),
('ranking.min_selection_score',    '4.0',  'DECIMAL', 'Minimum selectionScore to be eligible');
