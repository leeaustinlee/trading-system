package com.austin.trading.dto.internal;

/**
 * Input to {@link com.austin.trading.engine.ExecutionDecisionEngine}.
 *
 * <p>By the time this record is built, all upstream layers (regime, ranking,
 * setup, timing, risk) have already filtered the candidate.  The engine's job
 * is to confirm or downgrade the base action and record the audit trail.</p>
 *
 * <p>Codex contribution is read-only: {@code codexVetoed = true} downgrades
 * ENTER → SKIP; Codex cannot upgrade SKIP → ENTER.</p>
 */
public record ExecutionDecisionInput(
        RankedCandidate           rankedCandidate,    // contains symbol, codexVetoed, selectionScore
        String                    baseAction,         // FinalDecisionEngine output: ENTER/SKIP/REST/WATCH

        // Upstream layer decisions — used by ExecutionDecisionService for ID lookup
        MarketRegimeDecision      regimeDecision,
        SetupDecision             setupDecision,
        ExecutionTimingDecision   timingDecision,
        PortfolioRiskDecision     riskDecision
) {}
