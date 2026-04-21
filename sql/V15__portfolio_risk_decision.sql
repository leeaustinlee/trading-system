-- ── V15: P0.5 Risk Layer ──────────────────────────────────────────────────────
-- portfolio_risk_decision — portfolio-gate and per-candidate risk check output.
-- Note: ddl-auto=update creates this on first startup; this file is for DR / fresh-env.

CREATE TABLE IF NOT EXISTS portfolio_risk_decision (
    id                    BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trading_date          DATE            NOT NULL,
    symbol                VARCHAR(20)     NULL     COMMENT 'NULL for portfolio-gate rows',
    approved              TINYINT(1)      NOT NULL DEFAULT 0,
    block_reason          VARCHAR(50)     NULL     COMMENT 'PORTFOLIO_FULL|THEME_OVER_EXPOSED|ALREADY_HELD|null',
    open_position_count   INT             NOT NULL DEFAULT 0,
    max_positions         INT             NOT NULL DEFAULT 3,
    candidate_theme       VARCHAR(100)    NULL,
    theme_exposure_pct    DECIMAL(8,4)    NULL,
    payload_json          JSON            NULL,
    created_at            TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_risk_date_symbol   (trading_date, symbol),
    INDEX idx_risk_date_approved (trading_date, approved)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── score_config seeds for risk parameters (INSERT IGNORE = idempotent) ─────────
INSERT IGNORE INTO score_config (config_key, config_value, value_type, description) VALUES
('risk.max_theme_exposure_pct', '60.0', 'DECIMAL', 'RISK: max % of portfolio cost in a single theme before blocking new entries in that theme');
