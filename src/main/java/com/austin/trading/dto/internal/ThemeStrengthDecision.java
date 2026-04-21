package com.austin.trading.dto.internal;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Output of {@code ThemeStrengthEngine} (implemented in P1.1).
 *
 * <p>Defined here in P0.3 so that {@link SetupEvaluationInput} compiles with the
 * full field set.  In P0.3 callers pass {@code null}; the Setup engine applies
 * a conservative fallback ({@code tradable=true}) when absent.</p>
 *
 * <p>Theme stages:</p>
 * <ul>
 *   <li>{@code EARLY_EXPANSION} — theme just starting</li>
 *   <li>{@code MID_TREND}       — confirmed strength</li>
 *   <li>{@code LATE_EXTENSION}  — extended, watch decay</li>
 *   <li>{@code DECAY}           — fading, restrict entries</li>
 * </ul>
 */
public record ThemeStrengthDecision(
        LocalDate tradingDate,
        String themeTag,
        BigDecimal strengthScore,
        /** EARLY_EXPANSION | MID_TREND | LATE_EXTENSION | DECAY */
        String themeStage,
        String catalystType,
        boolean tradable,
        BigDecimal decayRisk,
        String reasonsJson
) {}
