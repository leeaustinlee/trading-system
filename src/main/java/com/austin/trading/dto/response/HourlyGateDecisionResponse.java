package com.austin.trading.dto.response;

import java.time.LocalDateTime;
import java.util.List;

public record HourlyGateDecisionResponse(
        LocalDateTime generatedAt,
        String marketGrade,
        String marketPhase,
        String decision,
        String hourlyGate,
        boolean shouldRun5mMonitor,
        boolean shouldNotify,
        String triggerEvent,
        String hourlyReason,
        String nextCheckFocus,
        List<String> reopenConditions,
        String decisionLock,
        int cooldownMinutes,
        String lastEventType,
        String timeDecayStage,
        String summaryForLog
) {
}
