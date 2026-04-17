package com.austin.trading.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalTime;

public record MonitorEvaluateRequest(
        @NotBlank String marketGrade,
        @NotBlank String decision,
        String marketPhase,
        String previousMonitorMode,
        String previousEventType,
        LocalTime evaluationTime,
        boolean hasPosition,
        boolean hasCandidate,
        boolean hasCriticalEvent,
        String decisionLock,
        String timeDecayStage
) {
}
