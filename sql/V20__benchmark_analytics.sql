-- P2.2 Benchmark Analytics: stores weekly strategy vs market/theme comparisons
CREATE TABLE IF NOT EXISTS benchmark_analytics (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    start_date              DATE          NOT NULL,
    end_date                DATE          NOT NULL,
    strategy_avg_return     DECIMAL(8,4)  COMMENT 'Average pnl% across all attributed trades in period',
    market_avg_gain         DECIMAL(8,4)  COMMENT 'Mean avg_gain_pct across all theme snapshots in period',
    traded_theme_avg_gain   DECIMAL(8,4)  COMMENT 'Mean avg_gain_pct for traded themes (v1: same as market)',
    market_alpha            DECIMAL(8,4)  COMMENT 'strategy_avg_return - market_avg_gain',
    theme_alpha             DECIMAL(8,4)  COMMENT 'strategy_avg_return - traded_theme_avg_gain',
    market_verdict          VARCHAR(20)   COMMENT 'OUTPERFORM | MATCH | UNDERPERFORM | UNKNOWN',
    theme_verdict           VARCHAR(20)   COMMENT 'OUTPERFORM | MATCH | UNDERPERFORM | UNKNOWN',
    trade_count             INT,
    payload_json            JSON,
    created_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_benchmark_period (start_date, end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
