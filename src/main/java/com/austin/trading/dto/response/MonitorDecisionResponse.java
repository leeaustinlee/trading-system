package com.austin.trading.dto.response;

public record MonitorDecisionResponse(
        String marketGrade,
        String marketPhase,
        String decision,
        String monitorMode,
        boolean shouldNotify,
        String triggerEvent,
        String monitorReason,
        String nextCheckFocus,
        String decisionLock,
        int cooldownMinutes,
        String lastEventType,
        String timeDecayStage,
        String summaryForLog
) {
}
