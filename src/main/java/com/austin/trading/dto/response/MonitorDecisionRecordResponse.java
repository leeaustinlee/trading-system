package com.austin.trading.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record MonitorDecisionRecordResponse(
        Long id,
        LocalDate tradingDate,
        LocalDateTime decisionTime,
        String monitorMode,
        Boolean shouldNotify,
        String triggerEvent,
        String payloadJson,
        LocalDateTime createdAt
) {
}
