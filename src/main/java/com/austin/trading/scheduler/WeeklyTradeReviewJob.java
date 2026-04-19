package com.austin.trading.scheduler;

import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.service.StrategyRecommendationService;
import com.austin.trading.service.TradeReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(prefix = "trading.scheduler.weekly-trade-review", name = "enabled", havingValue = "true")
public class WeeklyTradeReviewJob {

    private static final Logger log = LoggerFactory.getLogger(WeeklyTradeReviewJob.class);

    private final TradeReviewService tradeReviewService;
    private final StrategyRecommendationService recommendationService;
    private final SchedulerLogService schedulerLogService;

    public WeeklyTradeReviewJob(TradeReviewService tradeReviewService,
                                 StrategyRecommendationService recommendationService,
                                 SchedulerLogService schedulerLogService) {
        this.tradeReviewService = tradeReviewService;
        this.recommendationService = recommendationService;
        this.schedulerLogService = schedulerLogService;
    }

    @Scheduled(cron = "${trading.scheduler.weekly-trade-review-cron:0 0 19 * * FRI}",
               zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime trigger = LocalDateTime.now();
        String jobName = "WeeklyTradeReviewJob";
        try {
            int reviewed = tradeReviewService.generateForAllUnreviewed();
            var recs = recommendationService.generate(null);
            String summary = "reviewed=" + reviewed + ", recommendations=" + recs.size();
            log.info("[WeeklyTradeReview] {}", summary);
            schedulerLogService.success(jobName, trigger, LocalDateTime.now(), summary);
        } catch (Exception e) {
            schedulerLogService.failed(jobName, trigger, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }
}
