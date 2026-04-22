-- ============================================================
-- V22: v2.7 Session-Aware + Penalty 下修 + mainStream ranking boost
--
-- 動機：v2.6 MVP Refactor 後實盤驗證發現——
-- 1) Consensus 算法把 Java 結構分納入共識懲罰，扭曲強股（已在程式碼層 v2.7 修正）
-- 2) NOT_IN_FINAL_PLAN / RR_BELOW_MIN / ENTRY_TOO_EXTENDED penalty 常態觸發
--    合計扣 3 分 → 主流強股被壓到 C bucket
-- 3) 08:30 試撮資料污染決策，需 Session-Aware 架構（程式碼層處理）
-- ============================================================

INSERT INTO score_config (config_key, config_value, description)
VALUES
    -- ─────────────────────────────────────────────────────────────────────
    -- v2.7 Penalty 下修（避免常態扣分）
    -- ─────────────────────────────────────────────────────────────────────
    ('penalty.not_in_final_plan',      '0.1',
     'v2.7: candidate 建立時 final_plan 常 false（待 Codex 圈選），不應常態扣 0.5'),
    ('penalty.rr_below_min',           '0.5',
     'v2.7: RR 用靜態 entry 算不準，降 penalty 待 Phase B 動態 RR'),
    ('penalty.entry_too_extended',     '0.0',
     'v2.7: entry extended 應由 ExecutionTimingEngine 處理，VetoEngine 不扣分'),

    -- ─────────────────────────────────────────────────────────────────────
    -- v2.7 mainStream 從 hard block 改 ranking boost（A6）
    -- ─────────────────────────────────────────────────────────────────────
    ('ranking.main_stream_boost',      '0.3',
     'v2.7: 候選股屬主流族群時 finalRank 加成；取代原 mainStream hard block'),

    -- ─────────────────────────────────────────────────────────────────────
    -- v2.7 Consensus 廢棄 keys（保留供追溯，v2.7 程式碼不再讀）
    -- ─────────────────────────────────────────────────────────────────────
    ('consensus.penalty_jc',           '0.25',
     'v2.7 DEPRECATED: 不再讀取（Consensus 去 Java 化）'),
    ('consensus.penalty_jx',           '0.20',
     'v2.7 DEPRECATED: 不再讀取（Consensus 去 Java 化）')

ON DUPLICATE KEY UPDATE
    config_value = VALUES(config_value),
    description  = VALUES(description);
