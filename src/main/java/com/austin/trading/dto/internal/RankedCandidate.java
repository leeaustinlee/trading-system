package com.austin.trading.dto.internal;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Output of {@link com.austin.trading.engine.StockRankingEngine} for a single
 * candidate symbol.
 *
 * <p>Each field is persisted to {@code stock_ranking_snapshot} so that every
 * entry decision can trace back to the ranking reason and score breakdown.</p>
 */
public record RankedCandidate(
        LocalDate tradingDate,
        String symbol,

        /** Weighted selection score [0, 10] = rs + theme + java + thesis components. */
        BigDecimal selectionScore,

        /** Relative-strength input (proxied in P0.2). */
        BigDecimal relativeStrengthScore,

        /** Theme-strength input (proxied in P0.2). */
        BigDecimal themeStrengthScore,

        /** Claude / Codex thesis score contribution. */
        BigDecimal thesisScore,

        String themeTag,

        /** True if any hard-reject rule fired (codexVetoed / cooldown / alreadyHeld). */
        boolean vetoed,

        /**
         * True for the top-N candidates that pass all hard-reject rules and
         * meet the minimum selection score threshold.  Only eligible candidates
         * proceed to the Setup layer.
         */
        boolean eligibleForSetup,

        /** Human-readable reason when {@code vetoed=true} or {@code eligibleForSetup=false}. */
        String rejectionReason,

        /** JSON object with per-component score details for audit. */
        String scoreBreakdownJson
) {}
