package com.austin.trading.scheduler;

import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.OrchestrationStep;
import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.service.StrategyRecommendationService;
import com.austin.trading.service.TradeReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(prefix = "trading.scheduler.weekly-trade-review", name = "enabled", havingValue = "true")
public class WeeklyTradeReviewJob {

    private static final Logger log = LoggerFactory.getLogger(WeeklyTradeReviewJob.class);

    private final TradeReviewService tradeReviewService;
    private final StrategyRecommendationService recommendationService;
    private final SchedulerLogService schedulerLogService;
    private final DailyOrchestrationService orchestrationService;

    public WeeklyTradeReviewJob(TradeReviewService tradeReviewService,
                                 StrategyRecommendationService recommendationService,
                                 SchedulerLogService schedulerLogService,
                                 DailyOrchestrationService orchestrationService) {
        this.tradeReviewService = tradeReviewService;
        this.recommendationService = recommendationService;
        this.schedulerLogService = schedulerLogService;
        this.orchestrationService = orchestrationService;
    }

    @Scheduled(cron = "${trading.scheduler.weekly-trade-review-cron:0 0 19 * * FRI}",
               zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime trigger = LocalDateTime.now();
        String jobName = "WeeklyTradeReviewJob";
        LocalDate today = LocalDate.now();
        OrchestrationStep step = OrchestrationStep.WEEKLY_TRADE_REVIEW;

        if (!orchestrationService.markRunning(today, step)) {
            log.info("[{}] Step {} already DONE today, skip.", jobName, step);
            return;
        }
        try {
            int reviewed = tradeReviewService.generateForAllUnreviewed();
            var recs = recommendationService.generate(null);
            String summary = "reviewed=" + reviewed + ", recommendations=" + recs.size();
            log.info("[WeeklyTradeReview] {}", summary);
            schedulerLogService.success(jobName, trigger, LocalDateTime.now(), summary);
            orchestrationService.markDone(today, step, summary);
        } catch (Exception e) {
            orchestrationService.markFailed(today, step, e.getMessage());
            schedulerLogService.failed(jobName, trigger, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }
}
