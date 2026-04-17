package com.austin.trading.dto.response;

import java.time.LocalDateTime;

public record NotificationResponse(
        Long id,
        LocalDateTime eventTime,
        String notificationType,
        String source,
        String title,
        String content,
        String payloadJson
) {
}
