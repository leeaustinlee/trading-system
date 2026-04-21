-- P2.1 Bounded Learning: approved parameter keys + timing tolerance config
INSERT INTO score_config (config_key, config_value, description)
VALUES
    -- Whitelist: only these keys may receive PARAM_ADJUST / TAG_FREQUENCY / RISK_CONTROL recommendations
    ('learning.allowed.keys',
     'position.review.max_holding_days,position.trailing.first_trail_pct,trading.cooldown.consecutive_loss_max,timing.tolerance.delay_pct_max',
     'Bounded learning: comma-separated list of config keys eligible for weekly recommendations'),

    -- Timing tolerance threshold for attribution-based analysis
    ('timing.tolerance.delay_pct_max',  '2.0',
     'BoundedLearning: max acceptable entry delay vs ideal entry (%). Attribution POOR if exceeded.'),

    -- Attribution analysis thresholds
    ('learning.timing_poor_rate_threshold',  '30.0',
     'BoundedLearning: if timingPoorRate > this %, emit timing recommendation'),
    ('learning.exit_poor_rate_threshold',    '30.0',
     'BoundedLearning: if exitPoorRate > this %, emit exit recommendation'),
    ('learning.min_attribution_sample',      '5',
     'BoundedLearning: minimum attribution records required for timing/exit analysis')
ON DUPLICATE KEY UPDATE config_value = VALUES(config_value);
