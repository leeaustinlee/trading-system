package com.austin.trading.dto.response;

import java.time.LocalDateTime;

public record NotificationResponse(
        Long id,
        LocalDateTime eventTime,
        String notificationType,
        String source,
        String title,
        String content,
        String payloadJson,
        String provider,
        String deliveryStatus,
        Boolean attempted,
        Boolean delivered,
        LocalDateTime attemptedAt,
        LocalDateTime deliveredAt,
        Integer providerHttpStatus,
        String providerMessageId,
        String errorCode,
        String errorBody,
        Integer retryCount
) {
    public NotificationResponse(Long id,
                                LocalDateTime eventTime,
                                String notificationType,
                                String source,
                                String title,
                                String content,
                                String payloadJson) {
        this(id, eventTime, notificationType, source, title, content, payloadJson,
                null, "CREATED", false, false, null, null,
                null, null, null, null, 0);
    }
}
