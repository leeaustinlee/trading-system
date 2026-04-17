package com.austin.trading.dto.response;

import java.time.LocalDateTime;
import java.util.List;

public record MigrationHealthResponse(
        LocalDateTime checkedAt,
        boolean ok,
        List<MigrationHealthItemResponse> checks
) {
}
