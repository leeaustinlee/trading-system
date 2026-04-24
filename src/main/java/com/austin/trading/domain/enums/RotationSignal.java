package com.austin.trading.domain.enums;

import java.util.Locale;

/**
 * v2 Theme Engine：題材資金輪動訊號。
 *
 * <p>分類規則（{@code theme-engine-implementation-spec.md §3.5}）：</p>
 * <ul>
 *   <li>{@link #IN}   — rotation_score ≥ 7.0 且 money_flow_score ≥ 6.5</li>
 *   <li>{@link #OUT}  — rotation_score ≤ 3.5 或 money_flow_score ≤ 4.0</li>
 *   <li>{@link #NONE} — 兩者皆不成立</li>
 *   <li>{@link #UNKNOWN} — 資料缺失時的保守預設（PR2+ gate 視為 WAIT）</li>
 * </ul>
 */
public enum RotationSignal {
    IN,
    OUT,
    NONE,
    UNKNOWN;

    public static RotationSignal parseOrUnknown(String raw) {
        if (raw == null) return UNKNOWN;
        String t = raw.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) return UNKNOWN;
        try {
            return RotationSignal.valueOf(t);
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
