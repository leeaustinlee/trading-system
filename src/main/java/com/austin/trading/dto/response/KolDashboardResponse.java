package com.austin.trading.dto.response;

import java.time.LocalDate;
import java.util.List;

public record KolDashboardResponse(
        LocalDate tradingDate,
        int signalCount,
        int themeCount,
        List<KolThemeSnapshotResponse> themes,
        LocalDate latestSignalDate,
        long staleDays,
        String dataFreshnessStatus,
        LocalDate latestDataDate,
        int signalCountToday,
        long signalCount7d,
        String warning
) {
}
