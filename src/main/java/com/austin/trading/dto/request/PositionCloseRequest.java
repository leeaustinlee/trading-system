package com.austin.trading.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PositionCloseRequest(
        @NotNull @DecimalMin("0.0001") BigDecimal closePrice,
        LocalDateTime closedAt
) {
}
