package com.austin.trading.scheduler;

import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.notify.LineTemplateService;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.MarketDataService;
import com.austin.trading.service.SchedulerLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(prefix = "trading.scheduler.premarket-notify", name = "enabled", havingValue = "true")
public class PremarketNotifyJob {

    private static final Logger log = LoggerFactory.getLogger(PremarketNotifyJob.class);

    private final MarketDataService marketDataService;
    private final CandidateScanService candidateScanService;
    private final LineTemplateService lineTemplateService;
    private final SchedulerLogService schedulerLogService;

    public PremarketNotifyJob(
            MarketDataService marketDataService,
            CandidateScanService candidateScanService,
            LineTemplateService lineTemplateService,
            SchedulerLogService schedulerLogService
    ) {
        this.marketDataService = marketDataService;
        this.candidateScanService = candidateScanService;
        this.lineTemplateService = lineTemplateService;
        this.schedulerLogService = schedulerLogService;
    }

    @Scheduled(cron = "${trading.scheduler.premarket-notify-cron:0 30 8 * * MON-FRI}",
               zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "PremarketNotifyJob";
        try {
            LocalDate today = LocalDate.now();

            // 市場概況
            MarketCurrentResponse market = marketDataService.getCurrentMarket().orElse(null);
            String marketSummary = market == null
                    ? "（盤前市場資料尚未就緒）"
                    : "行情等級：" + market.marketGrade() + "，階段：" + market.marketPhase();

            // 候選清單（前日盤後 5 檔）
            List<CandidateResponse> candidates =
                    candidateScanService.getCandidatesByDate(today.minusDays(1), 5);
            String candidateText = formatCandidates(candidates);

            lineTemplateService.notifyPremarket(marketSummary, candidateText, today);

            log.info("[PremarketNotifyJob] done, candidates={}", candidates.size());
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(),
                    "candidates=" + candidates.size());
        } catch (Exception e) {
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }

    private String formatCandidates(List<CandidateResponse> list) {
        if (list.isEmpty()) return "（無候選資料）";
        return list.stream()
                .map(c -> "  ▶ " + c.symbol() + " " + (c.stockName() == null ? "" : c.stockName())
                        + (c.entryPriceZone() == null ? "" : "  進場區：" + c.entryPriceZone()))
                .collect(Collectors.joining("\n"));
    }
}
