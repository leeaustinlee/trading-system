package com.austin.trading.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record NotificationCreateRequest(
        @NotNull LocalDateTime eventTime,
        @NotBlank String notificationType,
        @NotBlank String source,
        @NotBlank String title,
        @NotBlank String content,
        String payloadJson
) {
}
