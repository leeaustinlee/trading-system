package com.austin.trading.dto.internal;

/**
 * Input to {@link com.austin.trading.engine.ExecutionTimingEngine}.
 * Market context uses boolean signal flags from the scoring pipeline
 * (Codex/Claude research output) rather than raw price ticks.
 *
 * <p>{@code signalAgeDays = 0} means the setup was identified today.
 * Values > 0 indicate the setup was carried over from prior sessions.
 */
public record TimingEvaluationInput(
        RankedCandidate     candidate,
        SetupDecision       setup,          // null → timing blocks immediately (NO_SETUP)
        MarketRegimeDecision regime,

        // ── Market context flags (from FinalDecisionCandidateRequest) ──
        boolean nearDayHigh,       // price is near today's high
        boolean belowOpen,         // price has fallen below today's open
        boolean belowPrevClose,    // price is below yesterday's close
        boolean entryTriggered,    // explicit breakout / confirmation signal fired
        boolean volumeSpike,       // abnormal volume detected

        int signalAgeDays          // 0 = fresh today; ≥1 = carried over
) {}
