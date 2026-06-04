-- V57: Stop Outcome Ledger
-- Records what happened after a stop/trailing/review exit so the system can
-- learn washout-vs-true-breakdown patterns before changing production exits.

CREATE TABLE IF NOT EXISTS stop_outcome_ledger (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    paper_trade_id         BIGINT        NOT NULL,
    symbol                VARCHAR(20)   NOT NULL,
    stock_name             VARCHAR(120)  NULL,
    exit_date              DATE          NOT NULL,
    exit_reason            VARCHAR(40)   NOT NULL,
    exit_price             DECIMAL(12,4) NOT NULL,
    entry_date             DATE          NULL,
    entry_price            DECIMAL(12,4) NULL,
    theme_tag              VARCHAR(100)  NULL,
    strategy_type          VARCHAR(30)   NULL,
    return_1d_after_exit   DECIMAL(8,4)  NULL,
    return_3d_after_exit   DECIMAL(8,4)  NULL,
    return_5d_after_exit   DECIMAL(8,4)  NULL,
    return_10d_after_exit  DECIMAL(8,4)  NULL,
    max_return_after_exit  DECIMAL(8,4)  NULL,
    min_return_after_exit  DECIMAL(8,4)  NULL,
    outcome_label          VARCHAR(40)   NOT NULL DEFAULT 'PENDING_DATA',
    evidence_json          JSON          NULL,
    created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_stop_outcome_paper_trade (paper_trade_id),
    KEY idx_stop_outcome_symbol_exit_date (symbol, exit_date),
    KEY idx_stop_outcome_label (outcome_label),
    KEY idx_stop_outcome_exit_reason (exit_reason)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
