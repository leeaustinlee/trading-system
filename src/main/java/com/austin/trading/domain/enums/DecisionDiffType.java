package com.austin.trading.domain.enums;

import java.util.Locale;

/**
 * v2 Theme Engine Shadow Mode：legacy 決策 vs theme-aware 決策的差異分類。
 *
 * <h3>P0.2 修正後的主要分類（write-on-every-comparison）</h3>
 * <ul>
 *   <li>{@link #SAME_DECISION_SAME_SCORE}  — 雙邊決策相同 AND |scoreDiff| <= 0.1</li>
 *   <li>{@link #SAME_DECISION_SCORE_DIFF}  — 雙邊決策相同 AND |scoreDiff| > 0.1</li>
 *   <li>{@link #DIFF_DECISION}              — 雙邊最終決策不同（無單邊 veto 主導）</li>
 *   <li>{@link #LEGACY_VETO_V2_PASS}        — legacy 拒/WAIT、theme v2 PASS（恰好 legacy 單邊 veto）</li>
 *   <li>{@link #V2_VETO_LEGACY_PASS}        — legacy ENTER、theme v2 BLOCK（恰好 v2 單邊 veto）</li>
 * </ul>
 *
 * <h3>舊 PR5 分類（保留）</h3>
 * <p>仍保留 6 個舊 enum 值供 {@link com.austin.trading.service.ThemeShadowReportService}
 * 與歷史資料反序列化使用；新分類由 {@link com.austin.trading.service.ThemeShadowModeService#classify}
 * 直接寫入。</p>
 * <ul>
 *   <li>{@link #SAME_BUY}                 — legacy/theme 都 ENTER</li>
 *   <li>{@link #SAME_WAIT}                — legacy/theme 都 WAIT/REST</li>
 *   <li>{@link #LEGACY_BUY_THEME_BLOCK}   — legacy ENTER、theme veto BLOCK</li>
 *   <li>{@link #LEGACY_WAIT_THEME_BUY}    — legacy WAIT、theme PASS</li>
 *   <li>{@link #BOTH_BLOCK}               — 雙邊都封鎖</li>
 *   <li>{@link #CONFLICT_REVIEW_REQUIRED} — 其他混合狀態</li>
 * </ul>
 */
public enum DecisionDiffType {
    // ── P0.2 主要分類 ────────────────────────────────────────────────
    SAME_DECISION_SAME_SCORE,
    SAME_DECISION_SCORE_DIFF,
    DIFF_DECISION,
    LEGACY_VETO_V2_PASS,
    V2_VETO_LEGACY_PASS,

    // ── 舊 PR5 分類（保留供 ReportService / 歷史資料）─────────────────
    SAME_BUY,
    SAME_WAIT,
    LEGACY_BUY_THEME_BLOCK,
    LEGACY_WAIT_THEME_BUY,
    BOTH_BLOCK,
    CONFLICT_REVIEW_REQUIRED;

    public static DecisionDiffType parseOrConflict(String raw) {
        if (raw == null) return CONFLICT_REVIEW_REQUIRED;
        String t = raw.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) return CONFLICT_REVIEW_REQUIRED;
        try {
            return DecisionDiffType.valueOf(t);
        } catch (IllegalArgumentException e) {
            return CONFLICT_REVIEW_REQUIRED;
        }
    }
}
