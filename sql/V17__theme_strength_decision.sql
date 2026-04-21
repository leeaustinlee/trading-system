-- P1.1 Theme Strength Layer: theme tradability, stage, and decay decisions
CREATE TABLE IF NOT EXISTS theme_strength_decision (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    trading_date    DATE         NOT NULL,
    theme_tag       VARCHAR(100) NOT NULL,
    strength_score  DECIMAL(6,4),
    theme_stage     VARCHAR(30)  COMMENT 'EARLY_EXPANSION|MID_TREND|LATE_EXTENSION|DECAY',
    catalyst_type   VARCHAR(50),
    tradable        TINYINT(1)   NOT NULL DEFAULT 1,
    decay_risk      DECIMAL(6,4),
    reasons_json    JSON,
    payload_json    JSON,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_theme_str_date_tag (trading_date, theme_tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Seed default theme strength weights
INSERT INTO score_config (config_key, config_value, description)
VALUES
    ('theme.weight.market_behavior',        '0.40', 'ThemeStrengthEngine: market behavior weight'),
    ('theme.weight.heat',                   '0.30', 'ThemeStrengthEngine: Claude heat score weight'),
    ('theme.weight.continuation',           '0.20', 'ThemeStrengthEngine: Claude continuation score weight'),
    ('theme.weight.breadth',                '0.10', 'ThemeStrengthEngine: breadth (strong-stock ratio) weight'),
    ('theme.tradable.min_strength',         '3.0',  'ThemeStrengthEngine: minimum strength score to be tradable'),
    ('theme.decay.max_allowed',             '0.6',  'ThemeStrengthEngine: maximum decay risk before blocking'),
    ('theme.stage.late_extension_threshold','7.5',  'ThemeStrengthEngine: strength score threshold for LATE_EXTENSION stage')
ON DUPLICATE KEY UPDATE config_value = VALUES(config_value);
