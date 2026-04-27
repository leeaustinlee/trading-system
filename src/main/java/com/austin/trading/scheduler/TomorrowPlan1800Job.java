package com.austin.trading.scheduler;

import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.dto.response.PositionResponse;
import com.austin.trading.notify.NotificationFacade;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "trading.scheduler.tomorrow-plan", name = "enabled", havingValue = "true")
public class TomorrowPlan1800Job {

    private static final Logger log = LoggerFactory.getLogger(TomorrowPlan1800Job.class);

    private final MarketDataService marketDataService;
    private final CandidateScanService candidateScanService;
    private final NotificationFacade notificationFacade;
    private final SchedulerLogService schedulerLogService;
    private final DailyOrchestrationService orchestrationService;
    private final AiTaskService aiTaskService;
    private final PositionService positionService;
    private final PositionReviewService positionReviewService;

    public TomorrowPlan1800Job(
            MarketDataService marketDataService,
            CandidateScanService candidateScanService,
            NotificationFacade notificationFacade,
            SchedulerLogService schedulerLogService,
            DailyOrchestrationService orchestrationService,
            AiTaskService aiTaskService,
            PositionService positionService,
            PositionReviewService positionReviewService
    ) {
        this.marketDataService = marketDataService;
        this.candidateScanService = candidateScanService;
        this.notificationFacade = notificationFacade;
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

            log.info("[TomorrowPlan1800Job] TOMORROW_PLAN LINE deferred until final AI result is submitted.");

            String aiMd = aiTaskService.findLatestMarkdown(today, "T86_TOMORROW", "POSTMARKET");
            if (false && aiMd != null && aiMd.length() > 100) {
                String summary = aiMd.length() > 2500
                        ? aiMd.substring(0, 2500) + "\n...(更多內容請查看 AI task 詳細結果)"
                        : aiMd;
                notificationFacade.notifySystemAlert("18:00 隔日計畫 AI 摘要", summary);
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
            sb.append("市場背景\n")
                    .append("市場等級：").append(market.marketGrade())
                    .append("｜行情階段：").append(market.marketPhase())
                    .append("\n\n");
        }

        sb.append("候選股 ").append(candidates.size()).append(" 檔\n");
        if (candidates.isEmpty()) {
            sb.append("- 目前沒有候選股，明早先觀察盤勢再決定。\n");
        } else {
            candidates.stream().limit(10).forEach(c -> {
                sb.append("- ").append(c.symbol());
                if (c.stockName() != null) sb.append(" ").append(c.stockName());
                if (c.entryPriceZone() != null) sb.append("｜進場區：").append(c.entryPriceZone());
                if (c.riskRewardRatio() != null) sb.append("｜RR：").append(c.riskRewardRatio());
                sb.append("\n");
            });
        }

        sb.append("\n明日執行\n");
        sb.append("08:30 先看盤前摘要；09:30 再看正式決策與盤中確認。\n");
        sb.append("來源：Trading System");

        sb.append("\n\n持倉明日建議\n");
        if (openPositions == null || openPositions.isEmpty()) {
            sb.append("- 目前沒有持倉。\n");
        } else {
            openPositions.stream().limit(10).forEach(p -> {
                sb.append("- ").append(p.symbol());
                if (p.stockName() != null) sb.append(" ").append(p.stockName());
                sb.append("｜").append(buildTomorrowAdvice(p)).append("\n");
            });
        }

        sb.append("\n盤後重點\n");
        sb.append("持倉已納入每日檢視與隔日計畫；若有停損停利缺值，系統會先補齊，再整理成明日建議。\n");
        return sb.toString();
    }

    private String buildTomorrowAdvice(PositionResponse p) {
        StringBuilder sb = new StringBuilder("續抱觀察");
        if (p.avgCost() != null) {
            sb.append("｜成本 ").append(formatNumber(p.avgCost()));
        }
        if (p.stopLossPrice() != null) {
            sb.append("｜防守 ").append(formatNumber(p.stopLossPrice()));
        }
        if (p.takeProfit1() != null) {
            sb.append("｜續強看 ").append(formatNumber(p.takeProfit1()));
        }
        if (p.takeProfit2() != null) {
            sb.append(" / ").append(formatNumber(p.takeProfit2()));
        }
        String note = p.note();
        if (note != null && !note.isBlank()) {
            if (note.contains("系統已依追價策略")) {
                sb.append("｜停損停利已依追價策略補齊");
            } else if (note.contains("系統已依一般策略")
                    || note.contains("setup defaults")
                    || note.contains("momentum defaults")) {
                sb.append("｜停損停利已由系統補齊");
            }
        }
        return sb.toString();
    }

    private String formatNumber(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
