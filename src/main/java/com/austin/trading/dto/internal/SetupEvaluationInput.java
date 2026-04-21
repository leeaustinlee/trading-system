package com.austin.trading.dto.internal;

import java.math.BigDecimal;

/**
 * Input contract for {@link com.austin.trading.engine.SetupEngine}.
 *
 * <p>Price-structure fields ({@code baseHigh}, {@code ma5}, etc.) must be
 * populated by the caller.  In P0.3 callers may pass {@code null} for fields
 * that are not yet available from the data pipeline; the engine applies
 * conservative fallbacks and marks the setup invalid when critical fields are
 * absent.</p>
 *
 * <p>{@code themeDecision} is {@code null} in P0.3 (ThemeStrengthEngine is P1.1);
 * the engine treats absent theme as tradable with unknown decay.</p>
 */
public record SetupEvaluationInput(
        RankedCandidate candidate,
        MarketRegimeDecision regime,

        /** Null in P0.3; provided by ThemeStrengthEngine in P1.1. */
        ThemeStrengthDecision themeDecision,

        /** Latest price (end-of-day close or intraday quote). */
        BigDecimal currentPrice,

        BigDecimal prevClose,

        /** 5-day simple moving average. */
        BigDecimal ma5,

        /** 10-day simple moving average. */
        BigDecimal ma10,

        /** High of the consolidation base (breakout reference). */
        BigDecimal baseHigh,

        /** Low of the consolidation base (invalidation reference). */
        BigDecimal baseLow,

        BigDecimal recentSwingHigh,
        BigDecimal recentSwingLow,

        /** 5-day average daily volume. */
        BigDecimal avgVolume5,

        /** Current day volume (or latest partial). */
        BigDecimal currentVolume,

        /** Number of days price has been consolidating near the base. */
        int consolidationDays,

        /** True if an event catalyst (earnings, announcement) is present. */
        boolean eventDriven
) {}
