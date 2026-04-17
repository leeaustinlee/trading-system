package com.austin.trading.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record HourlyGateDecisionRecordResponse(
        Long id,
        LocalDate tradingDate,
        LocalTime gateTime,
        String hourlyGate,
        Boolean shouldRun5mMonitor,
        String triggerEvent,
        String payloadJson,
        LocalDateTime createdAt
) {
}
