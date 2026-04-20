package com.austin.trading.scheduler;

import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.notify.LineTemplateService;
import com.austin.trading.service.AiTaskService;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.MarketDataService;
import com.austin.trading.service.OrchestrationStep;
import com.austin.trading.service.SchedulerLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "trading.scheduler.tomorrow-plan", name = "enabled", havingValue = "true")
public class TomorrowPlan1800Job {

    private static final Logger log = LoggerFactory.getLogger(TomorrowPlan1800Job.class);

    private final MarketDataService marketDataService;
    private final CandidateScanService candidateScanService;
    private final LineTemplateService lineTemplateService;
    private final SchedulerLogService schedulerLogService;
    private final DailyOrchestrationService orchestrationService;
    private final AiTaskService aiTaskService;

    public TomorrowPlan1800Job(
            MarketDataService marketDataService,
            CandidateScanService candidateScanService,
            LineTemplateService lineTemplateService,
            SchedulerLogService schedulerLogService,
            DailyOrchestrationService orchestrationService,
            AiTaskService aiTaskService
    ) {
        this.marketDataService = marketDataService;
        this.candidateScanService = candidateScanService;
        this.lineTemplateService = lineTemplateService;
        this.schedulerLogService = schedulerLogService;
        this.orchestrationService = orchestrationService;
        this.aiTaskService = aiTaskService;
    }

    @Scheduled(cron = "${trading.scheduler.tomorrow-plan-cron:0 30 18 * * MON-FRI}",
            zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "TomorrowPlan1800Job";
        LocalDate today = LocalDate.now();
        OrchestrationStep step = OrchestrationStep.TOMORROW_PLAN;

        if (!orchestrationService.markRunning(today, step)) {
            log.info("[{}] Step {} already DONE today, skip.", jobName, step);
            return;
        }

        try {
            MarketCurrentResponse market = marketDataService.getCurrentMarket().orElse(null);
            List<CandidateResponse> candidates = candidateScanService.getCurrentCandidates(10);

            lineTemplateService.notifyTomorrowPlan(buildMessage(today, market, candidates), today);

            String aiMd = aiTaskService.findLatestMarkdown(today, "T86_TOMORROW", "POSTMARKET");
            if (aiMd != null && aiMd.length() > 100) {
                String summary = aiMd.length() > 2500
                        ? aiMd.substring(0, 2500) + "\n...(內容過長已截斷，完整內容請看 AI task)"
                        : aiMd;
                lineTemplateService.notifySystemAlert("18:00 明日研究摘要", summary);
            }

            log.info("[TomorrowPlan1800Job] candidates={}", candidates.size());
            String msg = "candidates=" + candidates.size();
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), msg);
            orchestrationService.markDone(today, step, msg);
        } catch (Exception e) {
            orchestrationService.markFailed(today, step, e.getMessage());
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }

    private String buildMessage(LocalDate today, MarketCurrentResponse market, List<CandidateResponse> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("【明日計畫】").append(today).append("\n\n");
        if (market != null) {
            sb.append("📊 今日盤勢\n")
                    .append("等級：").append(market.marketGrade())
                    .append("｜階段：").append(market.marketPhase())
                    .append("\n\n");
        }

        sb.append("🎯 明日候選 ").append(candidates.size()).append(" 檔\n");
        if (candidates.isEmpty()) {
            sb.append("- 目前沒有有效候選，明日先觀察大盤與主流族群。\n");
        } else {
            candidates.stream().limit(10).forEach(c -> {
                sb.append("- ").append(c.symbol());
                if (c.stockName() != null) sb.append(" ").append(c.stockName());
                if (c.entryPriceZone() != null) sb.append("｜進場區：").append(c.entryPriceZone());
                if (c.riskRewardRatio() != null) sb.append("｜風報比：").append(c.riskRewardRatio());
                sb.append("\n");
            });
        }

        sb.append("\n📌 行動\n");
        sb.append("08:30 看盤前風向，09:30 依現價、題材一致性與風報比做最終決策。\n");
        sb.append("來源：Trading System");
        return sb.toString();
    }
}
