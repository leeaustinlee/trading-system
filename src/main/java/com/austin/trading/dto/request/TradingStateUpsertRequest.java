package com.austin.trading.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record TradingStateUpsertRequest(
        @NotNull LocalDate tradingDate,
        @NotBlank String marketGrade,
        @NotBlank String decisionLock,
        @NotBlank String timeDecayStage,
        @NotBlank String hourlyGate,
        @NotBlank String monitorMode,
        String payloadJson
) {
}
