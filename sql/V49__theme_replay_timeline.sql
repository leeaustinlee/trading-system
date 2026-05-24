-- MVP-5A Theme Replay Timeline Backend.
-- Safety: replay/read-only aggregation tables only. Does not affect BUY/SELL/ENTER,
-- FinalDecisionEngine, candidate gates, risk gates, or production scores.

CREATE TABLE IF NOT EXISTS theme_replay_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trading_date DATE NOT NULL,
    theme_tag VARCHAR(100) NOT NULL,
    lifecycle_stage VARCHAR(40) NULL,
    leader_symbol VARCHAR(20) NULL,
    leader_count INT NOT NULL DEFAULT 0,
    peer_count INT NOT NULL DEFAULT 0,
    breadth INT NOT NULL DEFAULT 0,
    taxonomy_gap_count INT NOT NULL DEFAULT 0,
    divergence_count INT NOT NULL DEFAULT 0,
    risk_rejected_count INT NOT NULL DEFAULT 0,
    research_universe_count INT NOT NULL DEFAULT 0,
    tradable_universe_count INT NOT NULL DEFAULT 0,
    replay_score DECIMAL(8,4) NULL,
    payload_json JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_theme_replay_snapshot_date_theme (trading_date, theme_tag),
    KEY idx_theme_replay_snapshot_date (trading_date),
    KEY idx_theme_replay_snapshot_leader (leader_symbol, trading_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS theme_replay_node (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trading_date DATE NOT NULL,
    theme_tag VARCHAR(100) NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    stock_name VARCHAR(120) NULL,
    research_role VARCHAR(40) NOT NULL,
    candidate_role VARCHAR(40) NULL,
    is_theme_leader BOOLEAN NOT NULL DEFAULT FALSE,
    leadership_only BOOLEAN NOT NULL DEFAULT FALSE,
    theme_leader_symbol VARCHAR(20) NULL,
    research_universe BOOLEAN NOT NULL DEFAULT TRUE,
    tradable_universe BOOLEAN NOT NULL DEFAULT FALSE,
    leader_tradable BOOLEAN NOT NULL DEFAULT FALSE,
    theme_importance_score DECIMAL(8,4) NULL,
    tradable_score DECIMAL(8,4) NULL,
    shadow_rank_score DECIMAL(8,4) NULL,
    divergence_score DECIMAL(8,4) NULL,
    taxonomy_gap_score DECIMAL(8,4) NULL,
    risk_rejected BOOLEAN NOT NULL DEFAULT FALSE,
    rejection_reason VARCHAR(500) NULL,
    safety_note VARCHAR(500) NULL,
    ai_governance_summary VARCHAR(1000) NULL,
    payload_json JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_theme_replay_node_date_theme_symbol (trading_date, theme_tag, symbol),
    KEY idx_theme_replay_node_date_theme (trading_date, theme_tag),
    KEY idx_theme_replay_node_role (trading_date, research_role),
    KEY idx_theme_replay_node_leader (theme_leader_symbol, trading_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS theme_replay_edge (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trading_date DATE NOT NULL,
    theme_tag VARCHAR(100) NOT NULL,
    from_symbol VARCHAR(20) NOT NULL,
    to_symbol VARCHAR(20) NOT NULL,
    edge_type VARCHAR(40) NOT NULL,
    confidence DECIMAL(8,4) NULL,
    reason VARCHAR(500) NULL,
    payload_json JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_theme_replay_edge_date_theme_from_to_type (trading_date, theme_tag, from_symbol, to_symbol, edge_type),
    KEY idx_theme_replay_edge_date_theme (trading_date, theme_tag),
    KEY idx_theme_replay_edge_from (from_symbol, trading_date),
    KEY idx_theme_replay_edge_to (to_symbol, trading_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
