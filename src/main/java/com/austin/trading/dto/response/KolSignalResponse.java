package com.austin.trading.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record KolSignalResponse(
        Long id,
        LocalDate tradingDate,
        String sourceKey,
        String sourceType,
        String sourceTitle,
        String signalStatus,
        String contentHash,
        boolean duplicate,
        boolean rawContentTruncated,
        LocalDateTime createdAt
) {
}
