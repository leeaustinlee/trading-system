-- P0.6 (2026-04-29): paper_trade Phase 1 forward-testing 升級
--
-- 目的：把 paper_trade 從「只記真 ENTER」擴成「shadow + live」雙軌追蹤，
-- 並補完 1d / 3d / 5d / 10d 報酬欄位給 forward-testing pipeline 使用。
--
-- 動作：純 ADD COLUMN + ADD INDEX，不改既有欄位、不刪資料。
-- P0.6d (review NEEDS_FIX-3)：所有 DDL 用 information_schema guard，整個 migration 重跑安全。

DROP PROCEDURE IF EXISTS p_v27_apply;
DELIMITER //
CREATE PROCEDURE p_v27_apply()
BEGIN
    -- ── 1. ADD COLUMNS（idempotent） ───────────────────────────────────
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'paper_trade'
                     AND column_name = 'is_shadow') THEN
        ALTER TABLE paper_trade ADD COLUMN is_shadow BOOLEAN NOT NULL DEFAULT FALSE
            COMMENT 'P0.6: true=shadow trade（非真 ENTER 但 final_score>=門檻寫入做 forward test）';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'paper_trade'
                     AND column_name = 'return_1d') THEN
        ALTER TABLE paper_trade ADD COLUMN return_1d DECIMAL(8,4) NULL
            COMMENT 'P0.6: 進場後 1 個交易日報酬 %（盤後回填）';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'paper_trade'
                     AND column_name = 'return_3d') THEN
        ALTER TABLE paper_trade ADD COLUMN return_3d DECIMAL(8,4) NULL
            COMMENT 'P0.6: 進場後 3 個交易日報酬 %';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'paper_trade'
                     AND column_name = 'return_5d') THEN
        ALTER TABLE paper_trade ADD COLUMN return_5d DECIMAL(8,4) NULL
            COMMENT 'P0.6: 進場後 5 個交易日報酬 %';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'paper_trade'
                     AND column_name = 'return_10d') THEN
        ALTER TABLE paper_trade ADD COLUMN return_10d DECIMAL(8,4) NULL
            COMMENT 'P0.6: 進場後 10 個交易日報酬 %';
    END IF;

    -- ── 2. ADD INDEXES（idempotent）─────────────────────────────────────
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'paper_trade'
                     AND index_name = 'idx_paper_shadow_status') THEN
        ALTER TABLE paper_trade ADD INDEX idx_paper_shadow_status (is_shadow, status);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'paper_trade'
                     AND index_name = 'idx_paper_entry_grade') THEN
        ALTER TABLE paper_trade ADD INDEX idx_paper_entry_grade (entry_grade);
    END IF;
END //
DELIMITER ;
CALL p_v27_apply();
DROP PROCEDURE p_v27_apply;
