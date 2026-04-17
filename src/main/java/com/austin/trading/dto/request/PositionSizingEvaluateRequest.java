package com.austin.trading.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PositionSizingEvaluateRequest(
        @NotBlank String marketGrade,
        @NotBlank String valuationMode,
        @NotNull Double baseCapital,
        @NotNull Double maxSinglePosition,
        @NotNull Double riskBudgetRatio,
        @NotNull Boolean nearDayHigh
) {
}
