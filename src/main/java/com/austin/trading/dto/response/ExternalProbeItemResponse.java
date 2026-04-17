package com.austin.trading.dto.response;

public record ExternalProbeItemResponse(
        String status,
        boolean success,
        String detail
) {
}
