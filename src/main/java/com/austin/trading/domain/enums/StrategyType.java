package com.austin.trading.domain.enums;

/**
 * v2.3 策略類型。
 * <ul>
 *   <li>{@code SETUP} — 回測進場、A+ 高分、嚴格 Veto；原有主策略。</li>
 *   <li>{@code MOMENTUM_CHASE} — 動能追價；不回測、倉位小、停損緊、持有短。</li>
 * </ul>
 */
public enum StrategyType {
    SETUP,
    MOMENTUM_CHASE;

    /** 大小寫/別名容忍的解析；預設 SETUP。 */
    public static StrategyType parse(String value) {
        if (value == null || value.isBlank()) return SETUP;
        String normalized = value.trim().toUpperCase().replace('-', '_');
        return switch (normalized) {
            case "MOMENTUM", "MOMENTUM_CHASE" -> MOMENTUM_CHASE;
            default -> SETUP;
        };
    }
}
