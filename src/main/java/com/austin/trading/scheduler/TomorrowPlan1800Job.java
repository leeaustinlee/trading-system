package com.austin.trading.scheduler;

import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.dto.response.PositionResponse;
import com.austin.trading.notify.LineTemplateService;
import com.austin.trading.service.AiTaskService;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.MarketDataService;
import com.austin.trading.service.OrchestrationStep;
import com.austin.trading.service.PositionReviewService;
import com.austin.trading.service.PositionService;
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
    private final PositionService positionService;
    private final PositionReviewService positionReviewService;

    public TomorrowPlan1800Job(
            MarketDataService marketDataService,
            CandidateScanService candidateScanService,
            LineTemplateService lineTemplateService,
            SchedulerLogService schedulerLogService,
            DailyOrchestrationService orchestrationService,
            AiTaskService aiTaskService,
            PositionService positionService,
            PositionReviewService positionReviewService
    ) {
        this.marketDataService = marketDataService;
        this.candidateScanService = candidateScanService;
        this.lineTemplateService = lineTemplateService;
        this.schedulerLogService = schedulerLogService;
        this.orchestrationService = orchestrationService;
        this.aiTaskService = aiTaskService;
        this.positionService = positionService;
        this.positionReviewService = positionReviewService;
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
            List<PositionResponse> openPositionsBeforeReview = positionService.getOpenPositions(20);
            int reviewedCount = 0;
            if (!openPositionsBeforeReview.isEmpty()) {
                reviewedCount = positionReviewService.reviewAllOpenPositions("DAILY").size();
            }
            List<PositionResponse> openPositions = positionService.getOpenPositions(20);

            lineTemplateService.notifyTomorrowPlan(buildMessage(today, market, candidates, openPositions), today);

            String aiMd = aiTaskService.findLatestMarkdown(today, "T86_TOMORROW", "POSTMARKET");
            if (aiMd != null && aiMd.length() > 100) {
                String summary = aiMd.length() > 2500
                        ? aiMd.substring(0, 2500) + "\n...(內容過長已截斷，完整內容請看 AI task)"
                        : aiMd;
                lineTemplateService.notifySystemAlert("18:00 明日研究摘要", summary);
            }

            log.info("[TomorrowPlan1800Job] candidates={}, reviewedPositions={}", candidates.size(), reviewedCount);
            String msg = "candidates=" + candidates.size() + ", reviewedPositions=" + reviewedCount;
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), msg);
            orchestrationService.markDone(today, step, msg);
        } catch (Exception e) {
            orchestrationService.markFailed(today, step, e.getMessage());
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }

    private String buildMessage(LocalDate today, MarketCurrentResponse market,
                                List<CandidateResponse> candidates,
                                List<PositionResponse> openPositions) {
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
        sb.append("\n?? 持倉關鍵價\n");
        if (openPositions == null || openPositions.isEmpty()) {
            sb.append("- 目前無持倉\n");
        } else {
            openPositions.stream().limit(10).forEach(p -> {
                sb.append("- ").append(p.symbol());
                if (p.stockName() != null) sb.append(" ").append(p.stockName());
                if (p.avgCost() != null) sb.append(" 成本 ").append(p.avgCost());
                if (p.stopLossPrice() != null) sb.append(" / 停損 ").append(p.stopLossPrice());
                if (p.takeProfit1() != null) sb.append(" / 停利1 ").append(p.takeProfit1());
                if (p.takeProfit2() != null) sb.append(" / 停利2 ").append(p.takeProfit2());
                sb.append("\n");
            });
        }

        sb.append("\n?? 隔日判讀\n");
        sb.append("盤後已重跑持倉檢視並更新缺少的停損/停利；隔日先看這些關鍵價，再判斷是否續抱、減碼或等待新倉。\n");
        return sb.toString();
    }
}
