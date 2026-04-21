package com.austin.trading.dto.internal;

import com.austin.trading.entity.PositionEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Input to {@link com.austin.trading.engine.TradeAttributionEngine}.
 * All upstream decision fields are nullable — attribution engine applies
 * conservative fallbacks when data is absent.
 */
public record TradeAttributionInput(
        PositionEntity position,

        // Upstream decision references (nullable when pipeline record not found)
        Long regimeDecisionId,
        String regimeType,

        Long setupDecisionId,
        String setupType,
        BigDecimal idealEntryPrice,

        Long timingDecisionId,
        String timingMode,

        Long themeDecisionId,
        String themeTag,
        String themeStage,

        Long executionDecisionId,

        // Realized trade metrics (from TradeReview or re-computed)
        BigDecimal mfePct,
        BigDecimal maePct,

        // Sizing context
        BigDecimal approvedRiskAmount   // null if risk decision not found
) {
    public LocalDate entryDate() {
        return position.getOpenedAt() != null ? position.getOpenedAt().toLocalDate() : null;
    }

    public LocalDate exitDate() {
        return position.getClosedAt() != null ? position.getClosedAt().toLocalDate() : null;
    }
}
