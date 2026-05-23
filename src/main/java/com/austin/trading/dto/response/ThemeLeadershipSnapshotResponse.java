package com.austin.trading.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Read-only theme leadership observability response.
 */
public record ThemeLeadershipSnapshotResponse(
        LocalDate tradingDate,
        String sourcePhase,
        int totalLeaders,
        int divergenceCount,
        Map<String, Long> byThemeCategory,
        Map<String, Long> bySubTheme,
        String safetyNote,
        LocalDateTime generatedAt,
        List<ThemeLeadershipItemResponse> items
) {
}
