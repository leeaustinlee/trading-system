-- P0.6 Execution Layer: final ENTER/SKIP/EXIT/WEAKEN decision log
CREATE TABLE IF NOT EXISTS execution_decision_log (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    trading_date        DATE         NOT NULL,
    symbol              VARCHAR(20)  NOT NULL,
    action              VARCHAR(10)  NOT NULL COMMENT 'ENTER|SKIP|EXIT|WEAKEN',
    reason_code         VARCHAR(50),
    codex_vetoed        TINYINT(1)   NOT NULL DEFAULT 0,
    regime_decision_id  BIGINT,
    ranking_snapshot_id BIGINT,
    setup_decision_id   BIGINT,
    timing_decision_id  BIGINT,
    risk_decision_id    BIGINT,
    payload_json        JSON,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_exec_date_symbol (trading_date, symbol),
    INDEX idx_exec_date_action (trading_date, action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
