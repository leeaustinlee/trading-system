package com.austin.trading.domain.enums;

import java.math.BigDecimal;

/**
 * v2.10 倉位分級（MVP：只影響 ADD 上限，不實際換算股數）。
 *
 * <ul>
 *     <li>{@link #TRIAL}  — 試單（0.3x 倉位建議係數）</li>
 *     <li>{@link #NORMAL} — 正常（0.7x）</li>
 *     <li>{@link #CORE}   — 主攻（1.0x）；若已 CORE，PositionManagementEngine 不再 ADD</li>
 * </ul>
 */
public enum PositionSizeLevel {
    TRIAL(new BigDecimal("0.3")),
    NORMAL(new BigDecimal("0.7")),
    CORE(new BigDecimal("1.0"));

    private final BigDecimal sizeFactor;

    PositionSizeLevel(BigDecimal sizeFactor) {
        this.sizeFactor = sizeFactor;
    }

    public BigDecimal sizeFactor() {
        return sizeFactor;
    }

    public static PositionSizeLevel parseOrDefault(String raw, PositionSizeLevel fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return PositionSizeLevel.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
