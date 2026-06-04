-- V59: Narrative-aware Position Thesis fields
-- Evidence-only/manual-confirm metadata. Does not affect BUY/ENTER/SELL production paths.

ALTER TABLE position_thesis_ledger
    ADD COLUMN theme_lifecycle VARCHAR(40) NULL AFTER thesis_summary,
    ADD COLUMN theme_heat DECIMAL(8,4) NULL AFTER theme_lifecycle,
    ADD COLUMN theme_breadth DECIMAL(8,4) NULL AFTER theme_heat,
    ADD COLUMN rotation_strength DECIMAL(8,4) NULL AFTER theme_breadth,
    ADD COLUMN narrative_heat DECIMAL(8,4) NULL AFTER rotation_strength,
    ADD COLUMN crowding_risk VARCHAR(20) NULL AFTER narrative_heat,
    ADD COLUMN institutional_alignment VARCHAR(30) NULL AFTER crowding_risk,
    ADD COLUMN wave_phase VARCHAR(40) NULL AFTER institutional_alignment,
    ADD COLUMN market_context JSON NULL AFTER wave_phase,
    ADD COLUMN sector_leadership VARCHAR(30) NULL AFTER market_context,
    ADD COLUMN theme_still_active BOOLEAN NULL AFTER sector_leadership,
    ADD COLUMN auto_buy_enabled BOOLEAN NOT NULL DEFAULT FALSE AFTER production_decision_allowed;
