package com.austin.trading.scheduler;

import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.service.regime.MarketIndexBackfillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * P0.5 — 每日 15:30（盤後 + WatchlistRefresh 之後、TomorrowPlan 之前）抓
 * TWSE TAIEX + 半導體代理日線並 upsert 至 {@code market_index_daily}。
 *
 * <p>抓最近 ~2 個月歷史，足以涵蓋 60-day MA 計算所需資料量；TWSE 偶爾會
 * 補/改舊月份資料，每天重抓本月+前月可保 1-2 月內資料新鮮。</p>
 *
 * <p>抓不到 TWSE（網路、stat != OK、JSON parse 例外）→ 記錄 warn，<b>不</b> throw
 * 出 {@link RuntimeException}，也不會清掉現有 DB 資料；最差情況是
 * RealDowngradeEvaluator 三個歷史 trigger 暫時不觸發（fail-safe）。</p>
 */
@Component
@ConditionalOnProperty(prefix = "trading.scheduler.market-index-data-prep",
        name = "enabled", havingValue = "true")
public class MarketIndexDataPrepJob {

    private static final Logger log = LoggerFactory.getLogger(MarketIndexDataPrepJob.class);

    private final MarketIndexBackfillService backfillService;
    private final SchedulerLogService        schedulerLogService;

    public MarketIndexDataPrepJob(MarketIndexBackfillService backfillService,
                                  SchedulerLogService schedulerLogService) {
        this.backfillService     = backfillService;
        this.schedulerLogService = schedulerLogService;
    }

    @Scheduled(
            cron = "${trading.scheduler.market-index-data-prep-cron:0 30 15 * * MON-FRI}",
            zone = "${trading.timezone:Asia/Taipei}"
    )
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "MarketIndexDataPrepJob";
        try {
            int upserted = backfillService.dailyRefresh(LocalDate.now());
            String msg = String.format("market_index_daily upserted=%d", upserted);
            log.info("[{}] {}", jobName, msg);
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), msg);
        } catch (Exception e) {
            log.warn("[{}] 失敗（fail-safe，不影響其他 job）: {}", jobName, e.getMessage(), e);
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            // 故意不 rethrow：market_index_daily 抓不到只是讓 trigger fail-safe 不觸發，
            // 不應該因此把整個 scheduler 的後續步驟卡住。
        }
    }
}
