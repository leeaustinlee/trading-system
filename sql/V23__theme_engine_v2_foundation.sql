-- v2 Theme Engine PR1：Shadow Mode 資料契約（僅表結構，無 seed）。
-- 參考：docs/theme-engine-implementation-spec.md §8.2 / docs/theme-shadow-mode-spec.md §4
--
-- PR1 限制：
--   1. 本檔只建立 Shadow Mode 相關兩張表，不改 legacy_final_score 任何欄位。
--   2. 不新增任何排程、feature flag，或決策流程。
--   3. JPA ddl-auto=update 亦會自動建立這兩張表；本檔供 production flyway / 手動 apply。
--
-- Rollback：DROP TABLE theme_shadow_daily_report; DROP TABLE theme_shadow_decision_log;

-- ── 1. theme_shadow_decision_log ────────────────────────────────────
-- 每檔候選股每天一筆，記錄 legacy vs theme 雙路徑決策差異。
CREATE TABLE IF NOT EXISTS theme_shadow_decision_log (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    trading_date        DATE          NOT NULL,
    symbol              VARCHAR(20)   NOT NULL,
    market_regime       VARCHAR(30)   NULL,

    legacy_final_score  DECIMAL(6,3)  NULL,
    theme_final_score   DECIMAL(6,3)  NULL,
    score_diff          DECIMAL(6,3)  NULL,

    legacy_decision     VARCHAR(20)   NOT NULL,
    theme_decision      VARCHAR(20)   NOT NULL,
    theme_veto_reason   VARCHAR(80)   NULL,
    decision_diff_type  VARCHAR(40)   NOT NULL,

    legacy_trace_json   JSON          NULL,
    theme_trace_json    JSON          NULL,

    generated_by        VARCHAR(40)   NOT NULL DEFAULT 'FinalDecisionService',
    generated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_shadow_date_symbol (trading_date, symbol),
    KEY idx_shadow_date_diff (trading_date, decision_diff_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 2. theme_shadow_daily_report ────────────────────────────────────
-- 每交易日一筆彙總，統計六種 decision_diff_type 數量 + score_diff 分布。
CREATE TABLE IF NOT EXISTS theme_shadow_daily_report (
    id                              BIGINT AUTO_INCREMENT PRIMARY KEY,
    trading_date                    DATE         NOT NULL,
    total_candidates                INT          NOT NULL DEFAULT 0,
    same_buy_count                  INT          NOT NULL DEFAULT 0,
    same_wait_count                 INT          NOT NULL DEFAULT 0,
    legacy_buy_theme_block_count    INT          NOT NULL DEFAULT 0,
    legacy_wait_theme_buy_count     INT          NOT NULL DEFAULT 0,
    both_block_count                INT          NOT NULL DEFAULT 0,
    conflict_review_required_count  INT          NOT NULL DEFAULT 0,
    avg_score_diff                  DECIMAL(6,3) NULL,
    p90_abs_score_diff              DECIMAL(6,3) NULL,
    top_conflicts_json              JSON         NULL,
    report_markdown                 TEXT         NULL,
    created_at                      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shadow_report_date (trading_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
