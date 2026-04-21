package com.austin.trading.dto.internal;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Input contract for {@link com.austin.trading.engine.StockRankingEngine}.
 *
 * <p>Built by {@code StockRankingService} from pre-scored
 * {@code FinalDecisionCandidateRequest} objects.  Fields
 * {@code relativeStrengthScore} and {@code themeStrengthScore} are proxies in
 * P0.2 (derived from baseScore and finalThemeScore); they will be replaced with
 * real values once {@code RelativeStrengthEngine} (P1) and
 * {@code ThemeStrengthEngine} (P1.1) are live.</p>
 */
public record RankCandidateInput(
        LocalDate tradingDate,
        String symbol,

        /** Java structural score [0, 10]. */
        BigDecimal javaStructureScore,

        /** Claude research score [0, 10]; nullable. */
        BigDecimal claudeScore,

        /** Codex review score [0, 10]; nullable. */
        BigDecimal codexScore,

        /**
         * Pre-computed final rank score from the legacy pipeline; carried for
         * backward-compatibility with the step-0.6 score-gap check in
         * {@code FinalDecisionService}.
         */
        BigDecimal finalRankScore,

        /**
         * Relative-strength proxy (P0.2): {@code baseScore} on [0, 10] scale.
         * Will be the output of RelativeStrengthEngine in P1.
         */
        BigDecimal relativeStrengthScore,

        /**
         * Theme-strength proxy (P0.2): {@code finalThemeScore} on [0, 10] scale.
         * Will be the output of ThemeStrengthEngine in P1.1.
         */
        BigDecimal themeStrengthScore,

        /** Theme tag for exposure-concentration checks. */
        String themeTag,

        /**
         * True if VetoEngine issued a hard veto.  In P0.2 this is the combined
         * VetoEngine result (not Codex-specific); Codex-only veto separation is
         * a P1.3 refactor task.  Rejection reason stored as {@code VETOED}.
         */
        boolean codexVetoed,

        /** True if symbol (or theme) is within the cooldown window. */
        boolean inCooldown,

        /** True if an OPEN position for this symbol already exists. */
        boolean alreadyHeld
) {}
