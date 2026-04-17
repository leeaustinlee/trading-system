-- Phase 5: AI 研究記錄表
CREATE TABLE ai_research_log (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    trading_date  DATE        NOT NULL,
    research_type VARCHAR(50) NOT NULL COMMENT 'PREMARKET/STOCK_EVAL/FINAL_DECISION/POSTMARKET/HOURLY_GATE',
    symbol        VARCHAR(20)  NULL     COMMENT '個股代號，市場層級研究為 NULL',
    prompt_summary VARCHAR(500) NULL,
    research_result LONGTEXT   NULL,
    model         VARCHAR(100) NULL,
    tokens_used   INT          NULL,
    created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_date_type (trading_date, research_type),
    INDEX idx_symbol    (symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
