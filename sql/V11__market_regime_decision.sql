-- ── V11: P0.1 Regime Layer ────────────────────────────────────────────────────
-- market_regime_decision  — authoritative regime classification per trading day.
-- Note: ddl-auto=update creates this on first startup; this file exists for
--       auditing, DR restore, and fresh-environment provisioning.

CREATE TABLE IF NOT EXISTS market_regime_decision (
    id                      BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trading_date            DATE            NOT NULL,
    evaluated_at            DATETIME(6)     NOT NULL,
    regime_type             VARCHAR(30)     NOT NULL COMMENT 'BULL_TREND|RANGE_CHOP|WEAK_DOWNTREND|PANIC_VOLATILITY',
    market_grade            VARCHAR(10)     NULL     COMMENT 'Legacy A/B/C grade captured at evaluation time (audit only)',
    trade_allowed           TINYINT(1)      NOT NULL DEFAULT 1,
    risk_multiplier         DECIMAL(6,3)    NULL,
    allowed_setup_types_json JSON           NULL     COMMENT 'JSON array of allowed setup type strings',
    summary                 VARCHAR(255)    NULL,
    reasons_json            JSON            NULL     COMMENT 'JSON array of classification rule hits',
    input_snapshot_json     JSON            NULL     COMMENT 'Full input field snapshot (incl. null/fallback flags)',
    market_snapshot_id      BIGINT          NULL     COMMENT 'Loose ref to market_snapshot.id (no FK)',
    version                 INT             NOT NULL DEFAULT 1,
    created_at              TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_regime_date      (trading_date),
    INDEX idx_regime_date_eval (trading_date, evaluated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── score_config seeds for regime thresholds (INSERT IGNORE = idempotent) ─────
INSERT IGNORE INTO score_config (config_key, config_value, value_type, description) VALUES
-- Bull classification
('regime.bull.min_breadth_ratio',               '0.55', 'DECIMAL', 'BULL_TREND: breadth_positive_ratio 最低門檻'),
('regime.bull.min_leaders_ratio',               '0.55', 'DECIMAL', 'BULL_TREND: leaders_strong_ratio 最低門檻'),
-- Range classification
('regime.range.max_ma_distance_pct',            '1.50', 'DECIMAL', 'RANGE_CHOP: MA 距離絕對值上限(%)，用於 reason 補充'),
-- Weak downtrend classification
('regime.weakdown.min_negative_breadth_ratio',  '0.55', 'DECIMAL', 'WEAK_DOWNTREND: breadth_negative_ratio 最低門檻'),
-- Panic classification
('regime.panic.volatility_threshold',           '2.50', 'DECIMAL', 'PANIC_VOLATILITY: intraday_volatility_pct 觸發門檻'),
('regime.panic.min_negative_breadth_ratio',     '0.75', 'DECIMAL', 'PANIC_VOLATILITY: breadth_negative_ratio 極端門檻'),
-- Risk multipliers per regime
('regime.risk_multiplier.bull',                 '1.00', 'DECIMAL', 'BULL_TREND 風險乘數 (1.0 = 全倉)'),
('regime.risk_multiplier.range',                '0.50', 'DECIMAL', 'RANGE_CHOP 風險乘數'),
('regime.risk_multiplier.weakdown',             '0.25', 'DECIMAL', 'WEAK_DOWNTREND 風險乘數'),
('regime.risk_multiplier.panic',                '0.00', 'DECIMAL', 'PANIC_VOLATILITY 風險乘數 (0 = 禁止交易)');
