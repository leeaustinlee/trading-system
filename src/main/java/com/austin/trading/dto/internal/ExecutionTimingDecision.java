package com.austin.trading.dto.internal;

import java.time.LocalDate;

/**
 * Output of {@link com.austin.trading.engine.ExecutionTimingEngine}.
 * Persisted as {@code execution_timing_decision} by {@link com.austin.trading.service.ExecutionTimingService}.
 *
 * <p>Only candidates with {@code approved = true} may proceed to
 * {@link com.austin.trading.engine.FinalDecisionEngine}.
 * Score alone cannot bypass a {@code approved = false} timing decision.</p>
 */
public record ExecutionTimingDecision(
        LocalDate tradingDate,
        String    symbol,
        String    setupType,        // mirrors SetupDecision.setupType; null when no setup

        boolean   approved,         // true = timing window open → may enter

        // BREAKOUT_READY | PULLBACK_BOUNCE | EVENT_LAUNCH | WAIT | STALE | NO_SETUP
        String    timingMode,

        // HIGH | MEDIUM | LOW  (regime riskMultiplier caps urgency)
        String    urgency,

        boolean   staleSignal,
        int       delayToleranceDays,
        int       signalAgeDays,

        String    rejectionReason,  // null when approved
        String    payloadJson
) {}
