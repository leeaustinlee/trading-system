package com.austin.trading.dto.response;

import java.time.LocalDateTime;

public record ScoreConfigResponse(
        Long id,
        String configKey,
        String configValue,
        String valueType,
        String description,
        LocalDateTime updatedAt
) {
}
