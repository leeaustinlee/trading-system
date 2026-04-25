-- ============================================================
-- V24: Require Codex for normal FinalDecision ENTER
--
-- Documents and workflow-correctness spec require Codex completion before
-- FinalDecision can emit a normal ENTER. Existing local DBs may still carry
-- the older false value from ScoreConfigService defaults, so this migration
-- makes the runtime value explicit and traceable.
-- ============================================================

INSERT INTO score_config (config_key, config_value, value_type, description)
VALUES (
    'final_decision.require_codex',
    'true',
    'BOOLEAN',
    'FinalDecision 前必須等到 Codex 審核；Codex 未完成不得輸出正式 ENTER'
)
ON DUPLICATE KEY UPDATE
    config_value = 'true',
    value_type = 'BOOLEAN',
    description = VALUES(description);
