-- ============================================================
-- V1b: JPA-managed tables not covered by V1__init_schema.sql
-- Run AFTER V1, BEFORE any numbered migration (V2+).
-- In production (ddl-auto: update) these are created at app
-- startup; this file exists only for clean-DB dry runs.
-- ============================================================

CREATE TABLE IF NOT EXISTS score_config (
    id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
    config_key   VARCHAR(100) NOT NULL UNIQUE,
    config_value VARCHAR(500) NOT NULL,
    value_type   VARCHAR(20),
    description  VARCHAR(500),
    updated_at   TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_score_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS watchlist_stock (
    id                    BIGINT        AUTO_INCREMENT PRIMARY KEY,
    symbol                VARCHAR(20)   NOT NULL,
    stock_name            VARCHAR(120),
    theme_tag             VARCHAR(100),
    sector                VARCHAR(100),
    source_type           VARCHAR(30)   NOT NULL,
    current_score         DECIMAL(8,4),
    highest_score         DECIMAL(8,4),
    watch_status          VARCHAR(20)   NOT NULL,
    first_seen_date       DATE          NOT NULL,
    last_seen_date        DATE          NOT NULL,
    observation_days      INT           NOT NULL DEFAULT 0,
    consecutive_strong_days INT         NOT NULL DEFAULT 0,
    promoted_at           DATETIME,
    dropped_at            DATETIME,
    drop_reason           VARCHAR(200),
    notes                 TEXT,
    payload_json          JSON,
    strategy_type         VARCHAR(20)   NOT NULL DEFAULT 'SETUP',
    created_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_watchlist_symbol (symbol),
    INDEX idx_watchlist_status (watch_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS trade_review (
    id                          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    position_id                 BIGINT       NOT NULL,
    symbol                      VARCHAR(20)  NOT NULL,
    entry_date                  DATE,
    exit_date                   DATE,
    entry_price                 DECIMAL(12,4),
    exit_price                  DECIMAL(12,4),
    pnl_pct                     DECIMAL(8,4),
    holding_days                INT,
    mfe_pct                     DECIMAL(8,4),
    mae_pct                     DECIMAL(8,4),
    market_condition            VARCHAR(10),
    review_grade                VARCHAR(5)   NOT NULL,
    primary_tag                 VARCHAR(50)  NOT NULL,
    secondary_tags_json         JSON,
    strengths_json              JSON,
    weaknesses_json             JSON,
    improvement_suggestions_json JSON,
    ai_summary                  TEXT,
    reviewer_type               VARCHAR(20)  NOT NULL,
    review_version              INT          NOT NULL DEFAULT 1,
    score_snapshot_json         JSON,
    market_snapshot_json        JSON,
    theme_snapshot_json         JSON,
    created_at                  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_trade_review_position (position_id),
    INDEX idx_trade_review_symbol   (symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS theme_snapshot (
    id                      BIGINT        AUTO_INCREMENT PRIMARY KEY,
    trading_date            DATE          NOT NULL,
    theme_tag               VARCHAR(100)  NOT NULL,
    theme_category          VARCHAR(50),
    market_behavior_score   DECIMAL(6,3),
    total_turnover          DECIMAL(18,2),
    avg_gain_pct            DECIMAL(8,4),
    strong_stock_count      INT,
    leading_stock_symbol    VARCHAR(20),
    theme_heat_score        DECIMAL(6,3),
    theme_continuation_score DECIMAL(6,3),
    driver_type             VARCHAR(50),
    risk_summary            VARCHAR(500),
    final_theme_score       DECIMAL(6,3),
    ranking_order           INT,
    payload_json            JSON,
    created_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_theme_snapshot_date_tag (trading_date, theme_tag),
    INDEX idx_theme_snapshot_date (trading_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS stock_theme_mapping (
    id             BIGINT        AUTO_INCREMENT PRIMARY KEY,
    symbol         VARCHAR(20)   NOT NULL,
    stock_name     VARCHAR(120),
    theme_tag      VARCHAR(100)  NOT NULL,
    sub_theme      VARCHAR(100),
    theme_category VARCHAR(50),
    source         VARCHAR(50),
    confidence     DECIMAL(4,2),
    is_active      TINYINT(1)    NOT NULL DEFAULT 1,
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_stm_symbol   (symbol),
    INDEX idx_stm_theme    (theme_tag),
    INDEX idx_stm_active   (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS strategy_recommendation (
    id                       BIGINT        AUTO_INCREMENT PRIMARY KEY,
    recommendation_type      VARCHAR(50)   NOT NULL,
    target_key               VARCHAR(100)  NOT NULL,
    current_value            VARCHAR(50),
    suggested_value          VARCHAR(50),
    confidence_level         VARCHAR(10)   NOT NULL,
    reason                   VARCHAR(1000),
    supporting_metrics_json  JSON,
    source_run_id            BIGINT,
    status                   VARCHAR(20)   NOT NULL DEFAULT 'NEW',
    created_at               TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_strategy_rec_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS position_review_log (
    id               BIGINT        AUTO_INCREMENT PRIMARY KEY,
    position_id      BIGINT        NOT NULL,
    symbol           VARCHAR(20)   NOT NULL,
    review_date      DATE          NOT NULL,
    review_time      TIME,
    review_type      VARCHAR(20)   NOT NULL,
    decision_status  VARCHAR(20)   NOT NULL,
    current_price    DECIMAL(12,4),
    entry_price      DECIMAL(12,4),
    pnl_pct          DECIMAL(8,4),
    prev_stop_loss   DECIMAL(12,4),
    suggested_stop   DECIMAL(12,4),
    reason           VARCHAR(500),
    notified         TINYINT(1)    NOT NULL DEFAULT 0,
    payload_json     JSON,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_prl_position (position_id),
    INDEX idx_prl_date     (review_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS daily_orchestration_status (
    trading_date                DATE         NOT NULL PRIMARY KEY,
    step_premarket_data_prep    VARCHAR(20),
    step_premarket_notify       VARCHAR(20),
    step_open_data_prep         VARCHAR(20),
    step_final_decision         VARCHAR(20),
    step_hourly_gate            VARCHAR(20),
    step_five_minute_monitor    VARCHAR(20),
    step_midday_review          VARCHAR(20),
    step_aftermarket_review     VARCHAR(20),
    step_postmarket_data_prep   VARCHAR(20),
    step_postmarket_analysis    VARCHAR(20),
    step_watchlist_refresh      VARCHAR(20),
    step_t86_data_prep          VARCHAR(20),
    step_tomorrow_plan          VARCHAR(20),
    step_external_probe_health  VARCHAR(20),
    step_weekly_trade_review    VARCHAR(20),
    notes                       TEXT,
    updated_at                  TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS capital_config (
    id             BIGINT        PRIMARY KEY DEFAULT 1,
    available_cash DECIMAL(14,2),
    reserved_cash  DECIMAL(14,2),
    notes          TEXT,
    updated_at     TIMESTAMP     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
