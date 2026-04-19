-- PR-2: AI 任務佇列 + Claude/Codex 結果主動回寫
-- 主要功能：Java workflow 建立 PENDING 任務 → AI 認領 → 自動解析分數回寫 stock_evaluation
CREATE TABLE IF NOT EXISTS ai_task (
    id                        BIGINT        AUTO_INCREMENT PRIMARY KEY,
    trading_date              DATE          NOT NULL,
    task_type                 VARCHAR(30)   NOT NULL
        COMMENT 'PREMARKET / OPENING / MIDDAY / POSTMARKET / T86_TOMORROW / STOCK_EVAL / CODEX_REVIEW',
    target_symbol             VARCHAR(20)   NULL
        COMMENT 'STOCK_EVAL 才會有，其他 null',
    target_candidates_json    JSON          NULL
        COMMENT '[{symbol, stockName, themeTag, javaStructureScore}, ...]',
    status                    VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING / CLAUDE_RUNNING / CLAUDE_DONE / CODEX_RUNNING / CODEX_DONE / FINALIZED / FAILED / EXPIRED',
    prompt_summary            VARCHAR(2000) NULL,
    prompt_file_path          VARCHAR(500)  NULL,
    claude_result_markdown    LONGTEXT      NULL,
    claude_scores_json        JSON          NULL
        COMMENT '{"2303": 8.5, "3231": 7.8}',
    codex_result_markdown     LONGTEXT      NULL,
    codex_scores_json         JSON          NULL,
    codex_veto_symbols_json   JSON          NULL
        COMMENT '["6213"] 被 Codex veto 的標的',
    error_message             VARCHAR(1000) NULL,
    created_at                DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claude_started_at         DATETIME      NULL,
    claude_done_at            DATETIME      NULL,
    codex_started_at          DATETIME      NULL,
    codex_done_at             DATETIME      NULL,
    finalized_at              DATETIME      NULL,

    UNIQUE KEY uq_task (trading_date, task_type, target_symbol),
    INDEX idx_status    (status),
    INDEX idx_type_date (task_type, trading_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='AI 任務佇列（PR-2）';
