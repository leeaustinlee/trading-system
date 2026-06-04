package com.austin.trading.service;

import com.austin.trading.domain.enums.DataFreshnessStatus;
import com.austin.trading.dto.response.DataFreshnessSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

@Service
public class DataFreshnessService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Taipei");
    private final Clock clock;

    @Autowired
    public DataFreshnessService() {
        this(Clock.system(MARKET_ZONE));
    }

    public DataFreshnessService(Clock clock) {
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public DataFreshnessSnapshot evaluate(LocalDate latestDataDate, boolean futureDataDetected) {
        return evaluate(latestDataDate, today(), futureDataDetected);
    }

    public DataFreshnessSnapshot evaluate(LocalDate latestDataDate, LocalDate today, boolean futureDataDetected) {
        if (latestDataDate == null) {
            return new DataFreshnessSnapshot(null, 0, DataFreshnessStatus.EMPTY, futureDataDetected,
                    futureDataDetected ? "FUTURE_DATA_DETECTED_WITH_NO_VALID_DATA" : "NO_DATA");
        }
        long staleDays = Math.max(0, ChronoUnit.DAYS.between(latestDataDate, today));
        DataFreshnessStatus status = staleDays == 0 ? DataFreshnessStatus.LIVE : DataFreshnessStatus.STALE;
        String warning = futureDataDetected ? "FUTURE_DATA_DETECTED_IGNORED" : (status == DataFreshnessStatus.STALE ? "STALE_DATA" : null);
        return new DataFreshnessSnapshot(latestDataDate, staleDays, status, futureDataDetected, warning);
    }

    public long staleDays(LocalDate latestDataDate) {
        if (latestDataDate == null) return 0;
        return Math.max(0, ChronoUnit.DAYS.between(latestDataDate, today()));
    }
}
