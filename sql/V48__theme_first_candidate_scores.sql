-- MVP-4 Theme-first Candidate Universe 10 + role-aware / score shadow integration.
-- Safety: additive only. Does not modify FinalDecision/BUY/SELL/ENTER/risk gates.

ALTER TABLE candidate_stock
    ADD COLUMN candidate_role VARCHAR(40) NULL COMMENT 'MVP-4 role-aware candidate type; shadow/explainability only',
    ADD COLUMN theme_importance_score DECIMAL(8,4) NULL COMMENT 'Theme importance; does not imply tradability',
    ADD COLUMN tradable_score DECIMAL(8,4) NULL COMMENT 'Execution readiness; cannot override risk gates',
    ADD COLUMN shadow_rank_score DECIMAL(8,4) NULL COMMENT 'Shadow ranking / replay observability only',
    ADD COLUMN theme_leader_symbol VARCHAR(20) NULL COMMENT 'Theme leader that explains this candidate',
    ADD COLUMN is_theme_leader BOOLEAN NULL COMMENT 'True when candidate is the retained/current theme leader',
    ADD COLUMN leader_tradable BOOLEAN NULL COMMENT 'Leader tradability hint; never bypasses FinalDecision gates',
    ADD COLUMN leader_retention_reason VARCHAR(500) NULL COMMENT 'Reason leader remains in research universe',
    ADD COLUMN theme_trace_id VARCHAR(80) NULL COMMENT 'Trace id for theme-first replay/API observability';

CREATE INDEX idx_candidate_stock_theme_first
    ON candidate_stock (trading_date, candidate_role, theme_importance_score, tradable_score, shadow_rank_score);

CREATE INDEX idx_candidate_stock_theme_trace
    ON candidate_stock (theme_trace_id);

ALTER TABLE stock_theme_mapping
    ADD COLUMN role VARCHAR(40) NULL COMMENT 'THEME_LEADER/SECOND_LEADER/LOW_BASE_FOLLOWER/etc.',
    ADD COLUMN mapping_quality DECIMAL(8,4) NULL COMMENT 'Mapping confidence/quality for role-aware theme selection',
    ADD COLUMN last_seen_as_leader_at DATETIME NULL COMMENT 'Last time this symbol appeared as a theme leader',
    ADD COLUMN evidence_json JSON NULL COMMENT 'Evidence for role/theme mapping';

CREATE INDEX idx_stock_theme_mapping_role_quality
    ON stock_theme_mapping (theme_tag, role, mapping_quality);
