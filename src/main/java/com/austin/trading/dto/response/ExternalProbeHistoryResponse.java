package com.austin.trading.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ExternalProbeHistoryResponse(
        Long id,
        LocalDateTime checkedAt,
        LocalDate taifexDate,
        boolean liveLine,
        boolean liveClaude,
        ExternalProbeItemResponse taifex,
        ExternalProbeItemResponse line,
        ExternalProbeItemResponse claude
) {
}
