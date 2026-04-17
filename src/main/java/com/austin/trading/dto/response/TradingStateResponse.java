package com.austin.trading.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record TradingStateResponse(
        Long id,
        LocalDate tradingDate,
        String marketGrade,
        String decisionLock,
        String timeDecayStage,
        String hourlyGate,
        String monitorMode,
        String payloadJson,
        LocalDateTime updatedAt
) {
}
