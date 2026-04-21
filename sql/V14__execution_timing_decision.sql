-- ── V14: P0.4 Timing Layer ──────────────────────────────────────────────────────
-- execution_timing_decision — per-candidate timing window check output.
-- Note: ddl-auto=update creates this on first startup; this file is for DR / fresh-env.

CREATE TABLE IF NOT EXISTS execution_timing_decision (
    id                    BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trading_date          DATE            NOT NULL,
    symbol                VARCHAR(20)     NOT NULL,
    setup_type            VARCHAR(30)     NULL     COMMENT 'Mirrors SetupDecision.setupType; null when NO_SETUP',
    approved              TINYINT(1)      NOT NULL DEFAULT 0,
    timing_mode           VARCHAR(30)     NOT NULL COMMENT 'BREAKOUT_READY|PULLBACK_BOUNCE|EVENT_LAUNCH|WAIT|STALE|NO_SETUP',
    urgency               VARCHAR(10)     NOT NULL COMMENT 'HIGH|MEDIUM|LOW',
    stale_signal          TINYINT(1)      NOT NULL DEFAULT 0,
    delay_tolerance_days  INT             NOT NULL DEFAULT 0,
    signal_age_days       INT             NOT NULL DEFAULT 0,
    rejection_reason      VARCHAR(255)    NULL,
    payload_json          JSON            NULL,
    created_at            TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_timing_date_symbol   (trading_date, symbol),
    INDEX idx_timing_date_approved (trading_date, approved)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── score_config seeds for timing parameters (INSERT IGNORE = idempotent) ────────
INSERT IGNORE INTO score_config (config_key, config_value, value_type, description) VALUES
-- Stale-signal thresholds per setup type
('timing.breakout.stale_days',  '3', 'INTEGER', 'BREAKOUT: max signal age in days before marked stale'),
('timing.pullback.stale_days',  '5', 'INTEGER', 'PULLBACK: max signal age in days before marked stale'),
('timing.event.stale_days',     '4', 'INTEGER', 'EVENT: max signal age in days before marked stale');
