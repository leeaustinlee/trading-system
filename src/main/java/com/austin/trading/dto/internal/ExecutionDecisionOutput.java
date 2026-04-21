package com.austin.trading.dto.internal;

import java.time.LocalDate;

/**
 * Output of {@link com.austin.trading.engine.ExecutionDecisionEngine}.
 * Persisted as {@code execution_decision_log} by {@link com.austin.trading.service.ExecutionDecisionService}.
 *
 * <p>Java is the sole authority for emitting {@code ENTER / SKIP / EXIT / WEAKEN}.
 * Upstream DB IDs ({@code regimeDecisionId} etc.) are populated by
 * {@link com.austin.trading.service.ExecutionDecisionService} after DB lookup.</p>
 */
public record ExecutionDecisionOutput(
        LocalDate tradingDate,
        String    symbol,

        // ENTER | SKIP | EXIT | WEAKEN
        String    action,

        // WHY this action: CODEX_VETO | BASE_ACTION_SKIP | BASE_ACTION_REST | CONFIRMED | etc.
        String    reasonCode,

        boolean   codexVetoed,

        // Upstream attribution IDs (null when not yet looked up or not applicable)
        Long      regimeDecisionId,
        Long      rankingSnapshotId,
        Long      setupDecisionId,
        Long      timingDecisionId,
        Long      riskDecisionId,

        String    payloadJson
) {}
