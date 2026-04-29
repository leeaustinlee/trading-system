-- P0.6 (2026-04-29): paper_trade Phase 1 forward-testing 升級
--
-- 目的：把 paper_trade 從「只記真 ENTER」擴成「shadow + live」雙軌追蹤，
-- 並補完 1d / 3d / 5d / 10d 報酬欄位給 forward-testing pipeline 使用。
--
-- 動作：純 ADD COLUMN，不改既有欄位、不刪資料。

ALTER TABLE paper_trade
  ADD COLUMN is_shadow BOOLEAN NOT NULL DEFAULT FALSE
    COMMENT 'P0.6: true=shadow trade（非真 ENTER 但 final_score>=門檻寫入做 forward test）';

ALTER TABLE paper_trade
  ADD COLUMN return_1d DECIMAL(8,4) NULL
    COMMENT 'P0.6: 進場後 1 個交易日報酬 %（盤後回填）';

ALTER TABLE paper_trade
  ADD COLUMN return_3d DECIMAL(8,4) NULL
    COMMENT 'P0.6: 進場後 3 個交易日報酬 %';

ALTER TABLE paper_trade
  ADD COLUMN return_5d DECIMAL(8,4) NULL
    COMMENT 'P0.6: 進場後 5 個交易日報酬 %';

ALTER TABLE paper_trade
  ADD COLUMN return_10d DECIMAL(8,4) NULL
    COMMENT 'P0.6: 進場後 10 個交易日報酬 %';

CREATE INDEX idx_paper_shadow_status ON paper_trade (is_shadow, status);
CREATE INDEX idx_paper_entry_grade ON paper_trade (entry_grade);
