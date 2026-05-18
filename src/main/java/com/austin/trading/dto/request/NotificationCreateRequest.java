package com.austin.trading.dto.request;

import com.austin.trading.notify.NotificationDeliveryResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record NotificationCreateRequest(
        @NotNull LocalDateTime eventTime,
        @NotBlank String notificationType,
        @NotBlank String source,
        @NotBlank String title,
        @NotBlank String content,
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
    public NotificationCreateRequest {
        deliveryStatus = (deliveryStatus == null || deliveryStatus.isBlank())
                ? NotificationDeliveryResult.STATUS_CREATED
                : deliveryStatus;
        attempted = attempted != null && attempted;
        delivered = delivered != null && delivered;
        retryCount = retryCount == null ? 0 : Math.max(0, retryCount);
    }

    public NotificationCreateRequest(LocalDateTime eventTime,
                                     String notificationType,
                                     String source,
                                     String title,
                                     String content,
                                     String payloadJson) {
        this(eventTime, notificationType, source, title, content, payloadJson,
                null, NotificationDeliveryResult.STATUS_CREATED, false, false,
                null, null, null, null, null, null, 0);
    }

    public NotificationCreateRequest(LocalDateTime eventTime,
                                     String notificationType,
                                     String source,
                                     String title,
                                     String content,
                                     String payloadJson,
                                     NotificationDeliveryResult deliveryResult) {
        this(eventTime, notificationType, source, title, content, payloadJson,
                deliveryResult == null ? null : deliveryResult.provider(),
                deliveryResult == null ? NotificationDeliveryResult.STATUS_CREATED : deliveryResult.status(),
                deliveryResult != null && deliveryResult.attempted(),
                deliveryResult != null && deliveryResult.delivered(),
                deliveryResult != null && deliveryResult.attempted() ? eventTime : null,
                deliveryResult != null && deliveryResult.delivered() ? eventTime : null,
                deliveryResult == null ? null : deliveryResult.httpStatus(),
                deliveryResult == null ? null : deliveryResult.providerMessageId(),
                deliveryResult == null ? null : deliveryResult.errorCode(),
                deliveryResult == null ? null : deliveryResult.errorBody(),
                deliveryResult == null ? 0 : deliveryResult.retryCount());
    }
}
