package com.austin.trading.domain.enums;

import java.util.Locale;

/**
 * v2 Theme Engine Shadow Mode：legacy 決策 vs theme-aware 決策的差異分類
 * （{@code theme-shadow-mode-spec.md §3}）。
 *
 * <ul>
 *   <li>{@link #SAME_BUY}                 — 雙邊都 ENTER</li>
 *   <li>{@link #SAME_WAIT}                — 雙邊都 WAIT/REST，且無題材 veto 衝突</li>
 *   <li>{@link #LEGACY_BUY_THEME_BLOCK}   — legacy ENTER、theme 因 veto 轉 BLOCK/REST/WAIT</li>
 *   <li>{@link #LEGACY_WAIT_THEME_BUY}    — legacy WAIT/REST、theme 反而 ENTER</li>
 *   <li>{@link #BOTH_BLOCK}               — 雙邊都封鎖，至少一個硬性 gate 相同</li>
 *   <li>{@link #CONFLICT_REVIEW_REQUIRED} — 其他或互斥混合狀態，需盤後人工檢視</li>
 * </ul>
 */
public enum DecisionDiffType {
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
