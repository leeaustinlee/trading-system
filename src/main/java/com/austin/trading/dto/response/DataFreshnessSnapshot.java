package com.austin.trading.dto.response;

import com.austin.trading.domain.enums.DataFreshnessStatus;

import java.time.LocalDate;

public record DataFreshnessSnapshot(
        LocalDate latestDataDate,
        long staleDays,
        DataFreshnessStatus dataFreshnessStatus,
        boolean futureDataDetected,
        String warning
) {
    public static DataFreshnessSnapshot empty() {
        return new DataFreshnessSnapshot(null, 0, DataFreshnessStatus.EMPTY, false, "NO_DATA");
    }
}
