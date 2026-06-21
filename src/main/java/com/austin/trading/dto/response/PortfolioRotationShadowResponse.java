package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PortfolioRotationShadowResponse(
        boolean shadowOnly,
        boolean advisoryOnly,
        boolean doesNotAffectBuySell,
        boolean doesNotMutatePositions,
        boolean doesNotAffectRiskGate,
        int requestedDays,
        LocalDate startDate,
        LocalDate endDate,
        int openPositionCount,
        int candidateCount,
        List<Item> items,
        List<String> dataGaps,
        String safetyNote
) {
    public static PortfolioRotationShadowResponse of(int requestedDays,
                                                     LocalDate startDate,
                                                     LocalDate endDate,
                                                     int openPositionCount,
                                                     int candidateCount,
                                                     List<Item> items,
                                                     List<String> dataGaps) {
        return new PortfolioRotationShadowResponse(
                true,
                true,
                true,
                true,
                true,
                requestedDays,
                startDate,
                endDate,
                openPositionCount,
                candidateCount,
                items == null ? List.of() : List.copyOf(items),
                dataGaps == null ? List.of() : List.copyOf(dataGaps),
                "SHADOW_ONLY advisory review; no order routing, no position mutation, no portfolio risk-gate change."
        );
    }

    public record Item(
            LocalDate candidateDate,
            String candidateSymbol,
            String candidateName,
            String candidateTheme,
            BigDecimal newCandidateScore,
            String weakestHoldingSymbol,
            String weakestHoldingName,
            String weakestHoldingTheme,
            BigDecimal weakestHoldingScore,
            BigDecimal lifecycleDifferential,
            BigDecimal opportunityDelta,
            boolean opportunityDeltaDataGapped,
            String shadowAction,
            String reason
    ) {
    }
}
