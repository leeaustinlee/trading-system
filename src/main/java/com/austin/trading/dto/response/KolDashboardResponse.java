package com.austin.trading.dto.response;

import java.time.LocalDate;
import java.util.List;

public record KolDashboardResponse(
        LocalDate tradingDate,
        int signalCount,
        int themeCount,
        List<KolThemeSnapshotResponse> themes
) {
}
