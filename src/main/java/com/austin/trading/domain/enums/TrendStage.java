package com.austin.trading.domain.enums;

import java.util.Locale;

/**
 * v2 Theme Engine：題材所處趨勢階段。
 *
 * <p>分類規則（{@code theme-engine-implementation-spec.md §3.5}）：</p>
 * <ul>
 *   <li>{@link #EARLY} — theme_strength ≥ 6.5 且 persistence_score &lt; 5.5</li>
 *   <li>{@link #MID}   — theme_strength ≥ 7.5 且 persistence_score ≥ 5.5</li>
 *   <li>{@link #LATE}  — theme_strength ≥ 7.0 且 persistence_score ≥ 7.0 且 freshness_score ≤ 4.5</li>
 *   <li>{@link #UNKNOWN} — parse 失敗 / null 保守預設（不可被使用作為決策條件）</li>
 * </ul>
 */
public enum TrendStage {
    EARLY,
    MID,
    LATE,
    UNKNOWN;

    /** 寬鬆解析：null / blank / 未定義值 → UNKNOWN（不拋錯）。 */
    public static TrendStage parseOrUnknown(String raw) {
        if (raw == null) return UNKNOWN;
        String t = raw.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) return UNKNOWN;
        try {
            return TrendStage.valueOf(t);
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
