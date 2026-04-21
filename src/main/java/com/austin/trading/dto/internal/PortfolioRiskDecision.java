package com.austin.trading.dto.internal;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Output of {@link com.austin.trading.engine.PortfolioRiskEngine}.
 *
 * <p>Two usage modes:</p>
 * <ul>
 *   <li><b>Portfolio-gate</b> — {@code symbol = null}; checks if any new entry is possible.</li>
 *   <li><b>Per-candidate</b> — {@code symbol} set; checks theme exposure / already-held.</li>
 * </ul>
 *
 * <p>{@link com.austin.trading.service.FinalDecisionService} must not allow candidates
 * with {@code approved = false} to reach position sizing or order emission.</p>
 */
public record PortfolioRiskDecision(
        LocalDate tradingDate,
        String    symbol,           // null for portfolio-gate decision

        boolean   approved,

        // PORTFOLIO_FULL | THEME_OVER_EXPOSED | ALREADY_HELD | null
        String    blockReason,

        int       openPositionCount,
        int       maxPositions,

        String    candidateTheme,   // theme of the evaluated candidate; null for gate decisions
        BigDecimal themeExposurePct, // current exposure of candidateTheme in portfolio

        String    payloadJson
) {}
