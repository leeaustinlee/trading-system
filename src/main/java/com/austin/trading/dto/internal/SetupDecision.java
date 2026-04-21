package com.austin.trading.dto.internal;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Output of {@link com.austin.trading.engine.SetupEngine} for one candidate.
 *
 * <p>A valid setup ({@code valid=true}) provides all price levels needed by the
 * Timing layer (P0.4) to confirm intraday entry.  An invalid setup is still
 * persisted with its {@code rejectionReason} for attribution.</p>
 */
public record SetupDecision(
        LocalDate tradingDate,
        String symbol,

        /** BREAKOUT_CONTINUATION | PULLBACK_CONFIRMATION | EVENT_SECOND_LEG */
        String setupType,

        boolean valid,

        /** Lower bound of the acceptable entry price zone. */
        BigDecimal entryZoneLow,

        /** Upper bound of the acceptable entry price zone. */
        BigDecimal entryZoneHigh,

        /**
         * Ideal entry price — the exact level used to compute delay% in
         * the Timing layer and idealEntryPrice in attribution.
         */
        BigDecimal idealEntryPrice,

        /**
         * Price at which this setup is structurally invalidated.
         * A close below this level voids the setup.
         */
        BigDecimal invalidationPrice,

        /** Initial hard stop price (setup-type-specific %). */
        BigDecimal initialStopPrice,

        BigDecimal takeProfit1Price,
        BigDecimal takeProfit2Price,

        /** MA5_TRAIL | MA10_TRAIL | SWING_LOW — default MA5_TRAIL for breakout. */
        String trailingMode,

        /** Suggested maximum holding days (setup-specific). */
        Integer holdingWindowDays,

        String rejectionReason,

        /** JSON object with rule-pass/fail details for audit. */
        String payloadJson
) {}
