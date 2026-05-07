CREATE TABLE IF NOT EXISTS tuning_apply_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    recommendation_id BIGINT NOT NULL,
    applied_date DATE NOT NULL,
    lookback_days INT NOT NULL,
    decision_win_rate DECIMAL(12,4),
    decision_avg_return DECIMAL(12,4),
    decision_avg_mfe DECIMAL(12,4),
    decision_avg_mae DECIMAL(12,4),
    strategy_metrics_json JSON,
    gate_metrics_json JSON,
    score_bucket_metrics_json JSON,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_tas_recommendation (recommendation_id),
    INDEX idx_tas_applied_date (applied_date),
    CONSTRAINT fk_tas_recommendation FOREIGN KEY (recommendation_id)
        REFERENCES strategy_tuning_recommendation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tuning_after_metrics (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    recommendation_id BIGINT NOT NULL,
    evaluation_date DATE NOT NULL,
    horizon_days INT NOT NULL,
    sample_size INT NOT NULL,
    win_rate DECIMAL(12,4),
    avg_return DECIMAL(12,4),
    avg_mfe DECIMAL(12,4),
    avg_mae DECIMAL(12,4),
    avg_relative_return DECIMAL(12,4),
    benchmark_return DECIMAL(12,4),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_tam_recommendation (recommendation_id),
    INDEX idx_tam_eval (recommendation_id, evaluation_date, horizon_days),
    CONSTRAINT fk_tam_recommendation FOREIGN KEY (recommendation_id)
        REFERENCES strategy_tuning_recommendation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tuning_evaluation_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    recommendation_id BIGINT NOT NULL,
    evaluation_status VARCHAR(40) NOT NULL,
    evaluation_reason VARCHAR(1500),
    improvement_score DECIMAL(12,4),
    risk_score DECIMAL(12,4),
    final_decision VARCHAR(40) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ter_recommendation (recommendation_id),
    INDEX idx_ter_status (evaluation_status),
    CONSTRAINT fk_ter_recommendation FOREIGN KEY (recommendation_id)
        REFERENCES strategy_tuning_recommendation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
