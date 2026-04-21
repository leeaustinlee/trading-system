package com.austin.trading.dto.internal;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Output of {@link com.austin.trading.engine.TradeAttributionEngine}.
 * Persisted as {@code trade_attribution} by {@link com.austin.trading.service.TradeAttributionService}.
 */
public record TradeAttributionOutput(
        Long positionId,
        String symbol,
        LocalDate entryDate,
        LocalDate exitDate,

        // Pipeline context
        String setupType,
        String regimeType,
        String themeStage,
        String timingMode,

        // Entry quality
        BigDecimal idealEntryPrice,
        BigDecimal actualEntryPrice,
        /** (actualEntry - idealEntry) / idealEntry * 100; null if idealEntry unknown. */
        BigDecimal delayPct,

        // Trade excursion
        BigDecimal mfePct,
        BigDecimal maePct,
        BigDecimal pnlPct,

        // Quality assessments: GOOD | FAIR | POOR | UNKNOWN
        String timingQuality,
        String exitQuality,
        String sizingQuality,

        // Upstream attribution IDs (plain Long, no FK constraint)
        Long regimeDecisionId,
        Long setupDecisionId,
        Long timingDecisionId,
        Long themeDecisionId,
        Long executionDecisionId,

        String payloadJson
) {}
