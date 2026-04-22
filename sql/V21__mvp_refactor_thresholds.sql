-- ============================================================
-- V21: MVP Refactor — scoring/veto/portfolio 門檻下修 + penalty/decision 新 config
--
-- 動機：現況系統過度教科書化（A+ only → REST），出手率過低、主升段參與率差。
-- 本 migration 把多項 hard veto 觸發門檻放寬，並新增 penalty.* / decision.* key
-- 供 Phase 2 (VetoEngine 分流)、Phase 3 (三層分級) 使用。
-- ============================================================

INSERT INTO score_config (config_key, config_value, description)
VALUES
    -- ─────────────────────────────────────────────────────────────────────
    -- Scoring 門檻下修（讓 A 候選可交易、B 候選可試單）
    -- ─────────────────────────────────────────────────────────────────────
    ('scoring.rr_min_grade_b',         '1.8',
     'MVP: B 市場 RR 門檻；原 2.5 過嚴，實盤波段多落 1.5-2.2'),
    ('scoring.rr_min_grade_a',         '1.8',
     'MVP: A 市場 RR 門檻；保持 1.8（live 值已非 2.2）'),
    ('scoring.rr_min_ap',              '2.2',
     'MVP: A+ 門票 RR；原 2.5 保留但略降'),
    ('scoring.grade_ap_min',           '8.5',
     'MVP: A+ finalRankScore 門檻；原 8.8'),
    ('scoring.grade_a_min',            '7.6',
     'MVP: A finalRankScore 門檻；原 8.2 → 讓 A 真正可交易'),
    ('scoring.grade_b_min',            '6.8',
     'MVP: B finalRankScore 門檻；原 7.4 → 讓 B 成為試單層'),

    -- ─────────────────────────────────────────────────────────────────────
    -- Veto 門檻放寬（部分項目後續由 Phase 2 改成 soft penalty）
    -- ─────────────────────────────────────────────────────────────────────
    ('veto.theme_rank_max',            '4',
     'MVP: theme rank 門檻；原 2 過嚴，容納主流擴散與次主流'),
    ('veto.final_theme_score_min',     '6.5',
     'MVP: theme score 門檻；原 7.5'),
    ('veto.codex_score_min',           '5.5',
     'MVP: Codex 分數門檻；原 6.5 → AI 低分應降權而非封殺'),
    ('veto.require_theme',             'false',
     'MVP: 原 true 會把事件驅動個股直接 veto；改 false + penalty'),

    -- ─────────────────────────────────────────────────────────────────────
    -- Portfolio 配置放寬
    -- ─────────────────────────────────────────────────────────────────────
    ('portfolio.max_open_positions',   '4',
     'MVP: 最大持倉；原 3 → 4 讓 1-2 週波段能容納主攻+補漲+事件股'),
    ('portfolio.same_theme_max',       '2',
     'MVP: 同主題持倉上限；原 1 → 2 可參與主流題材擴散'),
    ('portfolio.allow_new_when_full_strong', 'true',
     'MVP: 滿倉時若新標的明顯強於最弱持倉可替換；原 false'),

    -- ─────────────────────────────────────────────────────────────────────
    -- Penalty 新 key（供 Phase 2 VetoEngine 分流使用）
    -- 扣分方式：finalRankScore_afterPenalty = rawScore - sum(applicablePenalty)
    -- ─────────────────────────────────────────────────────────────────────
    ('penalty.rr_below_min',           '1.5',
     'Phase 2: RR 未達門檻的扣分；不再 hard veto'),
    ('penalty.no_theme',               '1.0',
     'Phase 2: 無 theme 扣分'),
    ('penalty.theme_not_top',          '0.8',
     'Phase 2: theme rank 超過 top 扣分'),
    ('penalty.theme_score_too_low',    '0.8',
     'Phase 2: theme score 低於門檻扣分'),
    ('penalty.codex_low',              '1.0',
     'Phase 2: Codex 分數低於門檻扣分'),
    ('penalty.score_divergence_high',  '1.5',
     'Phase 2: Java/Claude 分歧大扣分'),
    ('penalty.high_val_weak_market',   '0.8',
     'Phase 2: 高估值弱市扣分'),
    ('penalty.not_in_final_plan',      '0.5',
     'Phase 2: 未列 final plan 扣分'),

    -- ─────────────────────────────────────────────────────────────────────
    -- Decision 新 key（供 Phase 3 FinalDecisionEngine 三層分級使用）
    -- ─────────────────────────────────────────────────────────────────────
    ('decision.max_pick_aplus',        '2',
     'Phase 3: A+ bucket 最多選幾檔'),
    ('decision.max_pick_a',            '2',
     'Phase 3: A bucket 最多選幾檔'),
    ('decision.max_pick_b',            '1',
     'Phase 3: B bucket 最多選幾檔（試單）'),
    ('decision.position_factor_aplus', '1.0',
     'Phase 3: A+ 倉位係數（主攻）'),
    ('decision.position_factor_a',     '0.7',
     'Phase 3: A 倉位係數（正常）'),
    ('decision.position_factor_b',     '0.5',
     'Phase 3: B 倉位係數（試單）'),
    ('decision.require_entry_trigger', 'true',
     '保留：進場是否需 entryTriggered；Phase 3 不改'),

    -- ─────────────────────────────────────────────────────────────────────
    -- 更新 Bounded Learning 白名單，把新 keys 納入可調整範圍
    -- ─────────────────────────────────────────────────────────────────────
    ('learning.allowed.keys',
     'position.review.max_holding_days,position.trailing.first_trail_pct,trading.cooldown.consecutive_loss_max,timing.tolerance.delay_pct_max,scoring.rr_min_grade_b,scoring.rr_min_grade_a,scoring.grade_ap_min,scoring.grade_a_min,scoring.grade_b_min,veto.codex_score_min,veto.final_theme_score_min,veto.theme_rank_max,portfolio.max_open_positions,portfolio.same_theme_max,penalty.rr_below_min,penalty.no_theme,penalty.codex_low',
     'MVP: 擴大 bounded learning 白名單以納入新 scoring/veto/portfolio/penalty keys')

ON DUPLICATE KEY UPDATE
    config_value = VALUES(config_value),
    description  = VALUES(description);
