-- V26: market_index_daily — TWSE TAIEX + 半導體代理（預設 2330）歷史日線
--
-- 由 P0.5 引入。供 RealDowngradeEvaluator 三個 trigger（CONSEC_DOWN /
-- TAIEX_BELOW_60MA / SEMI_WEAK）查詢歷史收盤。資料由 MarketIndexDataPrepJob
-- 每日 15:30 抓 TWSE 落盤；首次部署時 startup auto-backfill 60 個交易日。
--
-- 不要動原本任何表，只新增。

CREATE TABLE IF NOT EXISTS market_index_daily (
    id            BIGINT          NOT NULL AUTO_INCREMENT,
    symbol        VARCHAR(20)     NOT NULL COMMENT 't00 = TAIEX，個股以代號（如 2330）',
    trading_date  DATE            NOT NULL,
    open_price    DECIMAL(12,4)   NULL,
    high_price    DECIMAL(12,4)   NULL,
    low_price     DECIMAL(12,4)   NULL,
    close_price   DECIMAL(12,4)   NOT NULL,
    volume        BIGINT          NULL,
    created_at    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_market_index_symbol_date (symbol, trading_date),
    KEY idx_market_index_date (trading_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
