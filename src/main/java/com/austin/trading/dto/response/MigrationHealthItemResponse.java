package com.austin.trading.dto.response;

public record MigrationHealthItemResponse(
        String key,
        boolean ok,
        String detail
) {
}
