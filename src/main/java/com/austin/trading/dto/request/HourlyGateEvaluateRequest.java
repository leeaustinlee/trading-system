package com.austin.trading.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalTime;

public record HourlyGateEvaluateRequest(
        @NotBlank String marketGrade,
        @NotBlank String decision,
        String previousMarketGrade,
        String previousDecision,
        String previousHourlyGate,
        String previousDecisionLock,
        String previousEventType,
        LocalTime evaluationTime,
        boolean hasPosition,
        boolean hasCandidate,
        boolean hasCriticalEvent
) {
}
