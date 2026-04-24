package com.austin.trading.domain.enums;

import java.util.Locale;

/**
 * v2.11 資金配置積極度分級（與 {@link PositionSizeLevel} 語意相同但用途不同）：
 *
 * <ul>
 *   <li>{@link #TRIAL}  試單：riskPctPerTrade 0.3%、maxPositionPct 10%</li>
 *   <li>{@link #NORMAL} 正常：riskPctPerTrade 0.6%、maxPositionPct 20%</li>
 *   <li>{@link #CORE}   主攻：riskPctPerTrade 1.0%、maxPositionPct 30%</li>
 * </ul>
 *
 * <p>Mapping 規則（CapitalAllocationService 決定）：</p>
 * <ul>
 *   <li>SELECT_BUY_NOW + finalRankScore ≥ 8.5 → CORE</li>
 *   <li>SELECT_BUY_NOW + finalRankScore ≥ 7.6 → NORMAL</li>
 *   <li>CONVERT_BUY / 其他弱訊 → TRIAL</li>
 *   <li>marketRegime=BEAR/WEAK → 最多 TRIAL；PANIC → 不開倉</li>
 * </ul>
 */
public enum AllocationMode {
    TRIAL,
    NORMAL,
    CORE;

    public static AllocationMode parseOrDefault(String raw, AllocationMode fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return AllocationMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
