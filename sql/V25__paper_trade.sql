-- V25: Paper Trade(Phase 1 forward live virtual position)
-- 與 backtest_trade 平行,但用途不同:
--   backtest_trade = 歷史回放(BacktestRunEntity 為 parent)
--   paper_trade   = forward live 虛擬倉(每日由 FinalDecision 觸發 → MTM job 平倉)
-- 兩者必須使用同一個 ExitRuleEvaluator(共用 com.austin.trading.engine.exit 包),否則無法對比。

CREATE TABLE IF NOT EXISTS paper_trade (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    trade_id            VARCHAR(40)     NOT NULL,

    -- 進場
    entry_date          DATE            NOT NULL,
    entry_time          TIME            NULL,
    symbol              VARCHAR(20)     NOT NULL,
    stock_name          VARCHAR(120)    NULL,
    entry_price         DECIMAL(12,4)   NOT NULL,
    position_shares     INT             NULL,
    position_amount     DECIMAL(18,2)   NULL,
    stop_loss_price     DECIMAL(12,4)   NULL,
    target1_price       DECIMAL(12,4)   NULL,
    target2_price       DECIMAL(12,4)   NULL,
    max_holding_days    INT             NOT NULL DEFAULT 5,

    source              VARCHAR(20)     NULL COMMENT 'CODEX / CLAUDE / HYBRID',
    strategy_type       VARCHAR(30)     NULL COMMENT 'SETUP / MOMENTUM_CHASE',
    theme_tag           VARCHAR(100)    NULL,
    final_decision_id   BIGINT          NULL,
    ai_task_id          BIGINT          NULL,
    final_rank_score    DECIMAL(5,2)    NULL,
    theme_heat_score    DECIMAL(5,2)    NULL,
    expectation_score   DECIMAL(5,2)    NULL,

    -- 出場
    exit_date           DATE            NULL,
    exit_time           TIME            NULL,
    exit_price          DECIMAL(12,4)   NULL,
    exit_reason         VARCHAR(30)     NULL COMMENT 'STOP_LOSS / TARGET_1 / TARGET_2 / TIME_EXIT / MANUAL / VOID',
    pnl_amount          DECIMAL(18,2)   NULL,
    pnl_pct             DECIMAL(8,4)    NULL,
    holding_days        INT             NULL,
    mfe_pct             DECIMAL(8,4)    NULL COMMENT 'max favorable excursion',
    mae_pct             DECIMAL(8,4)    NULL COMMENT 'max adverse excursion',

    status              VARCHAR(16)     NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / CLOSED / VOID',
    payload_json        JSON            NULL,

    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_paper_trade_trade_id (trade_id),
    KEY idx_paper_trade_status (status),
    KEY idx_paper_trade_entry_date (entry_date),
    KEY idx_paper_trade_symbol (symbol),
    KEY idx_paper_trade_strategy (strategy_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
