package com.austin.trading.scheduler;

import com.austin.trading.service.NarrativeShadowReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class NarrativeShadowReviewJob {
    private static final Logger log = LoggerFactory.getLogger(NarrativeShadowReviewJob.class);
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Taipei");

    private final NarrativeShadowReviewService reviewService;

    public NarrativeShadowReviewJob(NarrativeShadowReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Scheduled(cron = "0 45 15 ? * MON-FRI", zone = "Asia/Taipei")
    public void aggregateDailyShadowReview() {
        LocalDate date = LocalDate.now(MARKET_ZONE);
        var response = reviewService.aggregate(date);
        log.info("[NarrativeShadowReviewJob] date={} themes={} candidates={} warnings={} shadowOnly={} productionDecisionAllowed={}",
                date,
                response.themeSummary().size(),
                response.candidateImpact().size(),
                response.warnings().size(),
                response.shadowOnly(),
                response.productionDecisionAllowed());
    }
}
