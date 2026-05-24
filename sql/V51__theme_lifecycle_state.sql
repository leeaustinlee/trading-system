-- MVP-5C Theme Lifecycle Engine Replay-only.
-- Safety: lifecycle state is replay/advisory-only. It must not affect BUY/SELL/ENTER,
-- FinalDecisionEngine, candidate gates, risk gates, production ranking, production scores,
-- RR gates, stop-loss, market gate, or tradable promotion.

CREATE TABLE IF NOT EXISTS theme_lifecycle_state (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trading_date DATE NOT NULL,
    theme_tag VARCHAR(100) NOT NULL,
    stage VARCHAR(40) NOT NULL,
    previous_stage VARCHAR(40) NULL,
    stage_changed BOOLEAN NOT NULL DEFAULT FALSE,
    stage_confidence DECIMAL(8,4) NULL,
    leader_count INT NOT NULL DEFAULT 0,
    breadth INT NOT NULL DEFAULT 0,
    volume_expansion DECIMAL(8,4) NULL,
    continuation_days INT NOT NULL DEFAULT 0,
    rotation_score DECIMAL(8,4) NULL,
    crowding_score DECIMAL(8,4) NULL,
    limit_up_density DECIMAL(8,4) NULL,
    narrative_density DECIMAL(8,4) NULL,
    institutional_flow_score DECIMAL(8,4) NULL,
    lifecycle_score DECIMAL(8,4) NULL,
    score_json JSON NULL,
    reason VARCHAR(1000) NULL,
    recommended_playbook_json JSON NULL,
    avoid_playbook_json JSON NULL,
    payload_json JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_theme_lifecycle_date_theme (trading_date, theme_tag),
    KEY idx_theme_lifecycle_date_stage (trading_date, stage),
    KEY idx_theme_lifecycle_theme_date (theme_tag, trading_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE theme_replay_snapshot
    ADD COLUMN lifecycle_score DECIMAL(8,4) NULL,
    ADD COLUMN lifecycle_reason VARCHAR(1000) NULL,
    ADD COLUMN recommended_playbook_json JSON NULL,
    ADD COLUMN avoid_playbook_json JSON NULL;

ALTER TABLE research_universe_item
    ADD COLUMN lifecycle_stage VARCHAR(40) NULL,
    ADD COLUMN lifecycle_score DECIMAL(8,4) NULL,
    ADD COLUMN lifecycle_advisory VARCHAR(1000) NULL;
