-- MVP-5D Theme-first Replay Metrics Engine.
-- Safety: metrics are replay/analytics-only. They must not affect BUY/SELL/ENTER,
-- FinalDecisionEngine, candidate gates, risk gates, production ranking, production scores,
-- RR gates, stop-loss, market gate, or tradable promotion.

CREATE TABLE IF NOT EXISTS theme_replay_metrics (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trading_date DATE NOT NULL,
    theme_tag VARCHAR(100) NOT NULL,

    -- Discovery metrics
    leader_retention_rate DECIMAL(8,4) NULL,
    peer_discovery_hit_rate DECIMAL(8,4) NULL,
    taxonomy_gap_discovery_count INT NOT NULL DEFAULT 0,
    research_universe_coverage DECIMAL(8,4) NULL,
    candidate_diversification INT NOT NULL DEFAULT 0,

    -- Trading safety metrics
    risk_rejected_leader_count INT NOT NULL DEFAULT 0,
    false_promotion_count INT NOT NULL DEFAULT 0,
    chase_high_avoided_count INT NOT NULL DEFAULT 0,
    risk_gate_bypass_count INT NOT NULL DEFAULT 0,
    leadership_only_entered_count INT NOT NULL DEFAULT 0,
    leader_tradable_false_enter_count INT NOT NULL DEFAULT 0,
    peer_shadow_direct_promotion_count INT NOT NULL DEFAULT 0,
    narrative_direct_enter_count INT NOT NULL DEFAULT 0,
    research_vs_tradable_separation_violation_count INT NOT NULL DEFAULT 0,

    -- Performance metrics (replay-only)
    post_signal_return_1d DECIMAL(10,4) NULL,
    post_signal_return_3d DECIMAL(10,4) NULL,
    post_signal_return_5d DECIMAL(10,4) NULL,
    max_drawdown_after_signal DECIMAL(10,4) NULL,
    pullback_entry_return DECIMAL(10,4) NULL,
    breakout_entry_return DECIMAL(10,4) NULL,
    low_base_follower_return DECIMAL(10,4) NULL,

    -- Lifecycle metrics
    stage_transition_accuracy DECIMAL(8,4) NULL,
    emerging_to_mainstream_hit_rate DECIMAL(8,4) NULL,
    overheated_avoidance_return DECIMAL(10,4) NULL,
    distribution_warning_lead_time DECIMAL(10,4) NULL,
    dead_theme_false_positive_rate DECIMAL(8,4) NULL,

    -- Governance metrics
    ai_governance_annotated_rate DECIMAL(8,4) NULL,
    rejection_reason_coverage DECIMAL(8,4) NULL,
    final_decision_trace_coverage DECIMAL(8,4) NULL,

    payload_json JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_theme_replay_metrics_date_theme (trading_date, theme_tag),
    KEY idx_theme_replay_metrics_date (trading_date),
    KEY idx_theme_replay_metrics_theme_date (theme_tag, trading_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
