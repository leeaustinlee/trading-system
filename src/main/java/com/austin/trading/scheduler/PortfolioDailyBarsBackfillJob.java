package com.austin.trading.scheduler;

import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.service.regime.MarketIndexSymbolBackfillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 每日盤後補齊 OPEN 持股的日線資料，供 portfolio health-v2 計算 MA/量比/相對強弱。
 */
@Component
@ConditionalOnProperty(prefix = "trading.scheduler.portfolio-daily-bars-backfill",
        name = "enabled", havingValue = "true")
public class PortfolioDailyBarsBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(PortfolioDailyBarsBackfillJob.class);

    private final MarketIndexSymbolBackfillService symbolBackfillService;
    private final SchedulerLogService schedulerLogService;

    public PortfolioDailyBarsBackfillJob(MarketIndexSymbolBackfillService symbolBackfillService,
                                         SchedulerLogService schedulerLogService) {
        this.symbolBackfillService = symbolBackfillService;
        this.schedulerLogService = schedulerLogService;
    }

    @Scheduled(
            cron = "${trading.scheduler.portfolio-daily-bars-backfill-cron:0 50 15 * * MON-FRI}",
            zone = "${trading.timezone:Asia/Taipei}"
    )
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "PortfolioDailyBarsBackfillJob";
        try {
            Map<String, Object> result = symbolBackfillService.backfillSymbols(
                    120,
                    null,
                    false,
                    false,
                    50);
            String msg = String.format(
                    "portfolio daily bars backfill resolved=%s upserted=%s benchmarkDataGap=%s",
                    result.get("resolvedSymbols"),
                    result.get("upsertedRows"),
                    result.get("benchmarkDataGap"));
            log.info("[{}] {}", jobName, msg);
            Number upserted = result.get("upsertedRows") instanceof Number n ? n : 0;
            if (upserted.intValue() > 0) {
                schedulerLogService.successReal(jobName, triggerTime, LocalDateTime.now(), msg);
            } else {
                schedulerLogService.emptyData(jobName, triggerTime, LocalDateTime.now(), msg);
            }
        } catch (Exception e) {
            log.warn("[{}] failed-safe: {}", jobName, e.getMessage(), e);
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
        }
    }
}
