package com.austin.trading.dto.response;

import java.time.LocalDateTime;

public record KolSignalTraceResponse(
        Long id,
        Long signalId,
        String traceStage,
        String traceAction,
        String detailJson,
        LocalDateTime createdAt
) {
}
