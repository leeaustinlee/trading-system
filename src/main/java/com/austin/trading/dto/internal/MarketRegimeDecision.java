package com.austin.trading.dto.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Output contract of {@link com.austin.trading.engine.MarketRegimeEngine}.
 *
 * <p>The four supported {@code regimeType} values are:
 * <ul>
 *   <li>{@code BULL_TREND}</li>
 *   <li>{@code RANGE_CHOP}</li>
 *   <li>{@code WEAK_DOWNTREND}</li>
 *   <li>{@code PANIC_VOLATILITY}</li>
 * </ul>
 * Downstream layers (ranking / setup / timing / risk) must read
 * {@code tradeAllowed} and {@code allowedSetupTypes} instead of deriving
 * policy from the legacy A/B/C grade string.</p>
 *
 * <p>{@code id} is {@code null} for the engine result before persistence;
 * persisted decisions (loaded from repo) include the DB id.</p>
 */
public record MarketRegimeDecision(
        Long id,
        LocalDate tradingDate,
        LocalDateTime evaluatedAt,
        String regimeType,
        String marketGrade,
        boolean tradeAllowed,
        BigDecimal riskMultiplier,
        List<String> allowedSetupTypes,
        String summary,
        String reasonsJson,
        String inputSnapshotJson
) {
    /** Convenience ctor used by the engine before persistence (id unknown). */
    public MarketRegimeDecision(
            LocalDate tradingDate,
            LocalDateTime evaluatedAt,
            String regimeType,
            String marketGrade,
            boolean tradeAllowed,
            BigDecimal riskMultiplier,
            List<String> allowedSetupTypes,
            String summary,
            String reasonsJson,
            String inputSnapshotJson
    ) {
        this(null, tradingDate, evaluatedAt, regimeType, marketGrade, tradeAllowed,
                riskMultiplier, allowedSetupTypes, summary, reasonsJson, inputSnapshotJson);
    }
}
