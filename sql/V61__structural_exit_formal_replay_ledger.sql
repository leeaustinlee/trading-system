-- Phase 1.1b Formal Replay Ledger schema hardening.
-- Safe additive migration: does not alter production exit, alert, or auto-close behavior.

ALTER TABLE structural_exit_decision_log
    ADD COLUMN source_review_log_id BIGINT NULL AFTER evaluation_date,
    ADD COLUMN review_date DATE NULL AFTER source_review_log_id;

ALTER TABLE structural_exit_decision_log
    ADD KEY idx_struct_exit_review (source_review_log_id, mode),
    ADD KEY idx_struct_exit_mode_date (mode, evaluation_date);
