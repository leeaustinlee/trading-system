-- ── V13: P0.3 Setup Layer ─────────────────────────────────────────────────────
-- setup_decision_log — per-candidate setup validation output from SetupEngine.
-- Note: ddl-auto=update creates this on first startup; this file is for DR / fresh-env.

CREATE TABLE IF NOT EXISTS setup_decision_log (
    id                  BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trading_date        DATE            NOT NULL,
    symbol              VARCHAR(20)     NOT NULL,
    setup_type          VARCHAR(30)     NULL     COMMENT 'BREAKOUT_CONTINUATION|PULLBACK_CONFIRMATION|EVENT_SECOND_LEG|null',
    valid               TINYINT(1)      NOT NULL DEFAULT 0,
    entry_zone_low      DECIMAL(12,4)   NULL,
    entry_zone_high     DECIMAL(12,4)   NULL,
    ideal_entry_price   DECIMAL(12,4)   NULL,
    invalidation_price  DECIMAL(12,4)   NULL,
    initial_stop_price  DECIMAL(12,4)   NULL,
    take_profit_1_price DECIMAL(12,4)   NULL,
    take_profit_2_price DECIMAL(12,4)   NULL,
    trailing_mode       VARCHAR(20)     NULL     COMMENT 'MA5_TRAIL|MA10_TRAIL|SWING_LOW',
    holding_window_days INT             NULL,
    rejection_reason    VARCHAR(255)    NULL,
    payload_json        JSON            NULL,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_setup_date_symbol (trading_date, symbol),
    INDEX idx_setup_date_valid  (trading_date, valid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── score_config seeds for setup parameters (INSERT IGNORE = idempotent) ──────
INSERT IGNORE INTO score_config (config_key, config_value, value_type, description) VALUES
-- Breakout
('setup.breakout.volume_multiplier',    '1.50', 'DECIMAL', 'BREAKOUT: current volume must be >= this * avg5 volume'),
('setup.breakout.near_base_pct',        '2.00', 'DECIMAL', 'BREAKOUT: price allowed to be this % below baseHigh'),
('setup.breakout.holding_days',         '10',   'INTEGER', 'BREAKOUT: suggested max holding days'),
-- Pullback
('setup.pullback.max_depth_pct',        '8.00', 'DECIMAL', 'PULLBACK: max allowed pullback from recentSwingHigh (%)'),
('setup.pullback.holding_days',         '8',    'INTEGER', 'PULLBACK: suggested max holding days'),
-- Event Second Leg
('setup.event.min_consolidation_days',  '3',    'INTEGER', 'EVENT: min consolidation days after catalyst'),
('setup.event.max_consolidation_days',  '10',   'INTEGER', 'EVENT: max consolidation days after catalyst'),
('setup.event.holding_days',            '7',    'INTEGER', 'EVENT: suggested max holding days'),
-- Stop loss %
('setup.stop.breakout_pct',             '4.0',  'DECIMAL', 'BREAKOUT: initial stop % below idealEntry'),
('setup.stop.pullback_pct',             '5.0',  'DECIMAL', 'PULLBACK: initial stop % below idealEntry'),
('setup.stop.event_pct',                '4.0',  'DECIMAL', 'EVENT: initial stop % below idealEntry'),
-- Take profit %
('setup.tp1.default_pct',               '7.0',  'DECIMAL', 'TP1: default take-profit 1 % above idealEntry'),
('setup.tp2.default_pct',              '13.0',  'DECIMAL', 'TP2: default take-profit 2 % above idealEntry');
