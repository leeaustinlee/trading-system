-- V10: Momentum Chase 策略路徑（v2.3）
-- 對應 docs/momentum-chase-strategy-design.md
-- 原則：全部為 ADD COLUMN，default 兼容現有資料；不修改也不刪除既有欄位。

-- ── 1. candidate_stock：標記是否為 Momentum 候選 ────────────────────────────
ALTER TABLE candidate_stock
    ADD COLUMN is_momentum_candidate BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN momentum_flags_json   JSON    NULL COMMENT 'which of 5 basic conditions met';

-- ── 2. stock_evaluation：momentum_score + strategy_type ─────────────────────
ALTER TABLE stock_evaluation
    ADD COLUMN momentum_score            DECIMAL(4,2) NULL,
    ADD COLUMN momentum_sub_scores_json  JSON         NULL,
    ADD COLUMN strategy_type             VARCHAR(20)  NULL COMMENT 'SETUP | MOMENTUM_CHASE';

-- ── 3. final_decision：決策所屬策略 ──────────────────────────────────────────
ALTER TABLE final_decision
    ADD COLUMN strategy_type VARCHAR(20) NULL COMMENT 'SETUP | MOMENTUM_CHASE | MIXED';

-- ── 4. position：持倉所屬策略（影響出場邏輯） ───────────────────────────────
ALTER TABLE position
    ADD COLUMN strategy_type VARCHAR(20) NOT NULL DEFAULT 'SETUP';

-- ── 5. watchlist_stock：Momentum 可 bypass observationDays ───────────────────
ALTER TABLE watchlist_stock
    ADD COLUMN strategy_type VARCHAR(20) NOT NULL DEFAULT 'SETUP';

-- ── 6. score_config：Momentum 熱更新參數 ────────────────────────────────────
INSERT IGNORE INTO score_config (config_key, config_value, value_type, description) VALUES
('momentum.enabled',                'true',  'BOOLEAN', 'Momentum Chase 策略是否啟用'),
('momentum.observation_mode',       'true',  'BOOLEAN', '觀察模式：只 WATCH 不下單（上線 2 週用）'),
('momentum.entry_score_min',        '7.5',   'DECIMAL', 'Momentum 進場最低 score'),
('momentum.watch_score_min',        '5.0',   'DECIMAL', 'Momentum 觀察最低 score'),
('momentum.strong_score_min',       '9.0',   'DECIMAL', '強 Momentum 門檻（可加碼至 70%）'),
('momentum.max_picks_per_day',      '1',     'INTEGER', '每日最多幾檔 Momentum 進場'),
('momentum.position_size_ratio',    '0.5',   'DECIMAL', 'Momentum 相對 Setup 的倉位比例'),
('momentum.strong_position_ratio',  '0.7',   'DECIMAL', '強 Momentum 倉位比例'),
('momentum.stop_loss_pct',          '-0.025','DECIMAL', 'Momentum 預設停損 -2.5%'),
('momentum.take_profit_1_pct',      '0.06',  'DECIMAL', 'Momentum TP1 +6%'),
('momentum.take_profit_2_pct',      '0.10',  'DECIMAL', 'Momentum TP2 +10%'),
('momentum.max_holding_days',       '3',     'INTEGER', 'Momentum 最長持有日'),
('momentum.basic_conditions_min',   '3',     'INTEGER', '5 條基本篩選至少符合幾項才算 Momentum 候選'),
-- Veto penalty（對 MOMENTUM 把 SETUP 的 hard veto 降為扣分）
('momentum.veto_penalty.no_theme',        '1.0', 'DECIMAL', ''),
('momentum.veto_penalty.not_in_plan',     '0.5', 'DECIMAL', ''),
('momentum.veto_penalty.theme_low',       '0.5', 'DECIMAL', ''),
('momentum.veto_penalty.theme_not_in_top','0.5', 'DECIMAL', ''),
('momentum.veto_penalty.extended',        '1.0', 'DECIMAL', ''),
('momentum.veto_penalty.codex_low',       '0.5', 'DECIMAL', ''),
('momentum.veto_penalty.high_val',        '1.0', 'DECIMAL', ''),
('momentum.veto_penalty.time_decay_late', '0.5', 'DECIMAL', ''),
-- AI 強烈負評（保留 veto）
('momentum.veto.claude_score_min',  '4.0',   'DECIMAL', 'Claude 分數低於此則視為強烈負評（保留 veto）'),
('momentum.veto.risk_flag_hard',    'LIQUIDITY_TRAP,EARNINGS_MISS,INSIDER_SELLING,VOLUME_SPIKE_LONG_BLACK,SUSPENDED_WARN',
                                    'STRING',  'Claude riskFlags 含任一即視為強烈負評');
