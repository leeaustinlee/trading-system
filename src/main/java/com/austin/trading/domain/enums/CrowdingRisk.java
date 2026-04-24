package com.austin.trading.domain.enums;

import java.util.Locale;

/**
 * v2 Theme Engine：題材擁擠度風險。
 *
 * <p>分類規則（{@code theme-engine-implementation-spec.md §3.5}）：</p>
 * <ul>
 *   <li>{@link #HIGH} — trend_stage=LATE 且 freshness_score ≤ 4.0 且 price_breadth_score ≥ 7.5</li>
 *   <li>{@link #MID}  — trend_stage=MID 或 freshness_score ∈ (4.0, 6.0)</li>
 *   <li>{@link #LOW}  — 其他</li>
 *   <li>{@link #UNKNOWN} — 資料不足時的保守預設</li>
 * </ul>
 *
 * <p>Size factor（{@code spec §4.6}）：LOW=1.0、MID=0.85、HIGH=0.65。</p>
 */
public enum CrowdingRisk {
    LOW,
    MID,
    HIGH,
    UNKNOWN;

    public static CrowdingRisk parseOrUnknown(String raw) {
        if (raw == null) return UNKNOWN;
        String t = raw.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) return UNKNOWN;
        try {
            return CrowdingRisk.valueOf(t);
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
