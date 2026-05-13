package com.austin.trading.dto.request;

import java.time.LocalDate;
import java.util.Map;

public record KolSignalCreateRequest(
        LocalDate tradingDate,
        String sourceKey,
        String sourceType,
        String sourceTitle,
        String rawContent,
        Map<String, Object> payload
) {
}
