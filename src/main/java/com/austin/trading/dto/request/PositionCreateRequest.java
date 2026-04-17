package com.austin.trading.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PositionCreateRequest(
        @NotBlank String symbol,
        @NotBlank String side,
        @NotNull @DecimalMin("0.0001") BigDecimal qty,
        @NotNull @DecimalMin("0.0001") BigDecimal avgCost,
        @NotBlank String status,
        LocalDateTime openedAt,
        LocalDateTime closedAt,
        String payloadJson
) {
}
