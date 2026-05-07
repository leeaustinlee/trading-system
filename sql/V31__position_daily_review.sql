CREATE TABLE IF NOT EXISTS position_daily_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trading_date DATE NOT NULL,
    stock_id VARCHAR(20) NOT NULL,
    strength VARCHAR(20),
    risk VARCHAR(20),
    hold_decision VARCHAR(20),
    suggested_stop DECIMAL(12,4),
    suggested_take_profit DECIMAL(12,4),
    switch_flag VARCHAR(20),
    reason TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_position_daily_review_date (trading_date),
    INDEX idx_position_daily_review_stock (stock_id)
);
