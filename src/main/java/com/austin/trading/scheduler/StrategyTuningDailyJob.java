package com.austin.trading.scheduler;

import com.austin.trading.service.StrategyTuningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StrategyTuningDailyJob {
    private static final Logger log = LoggerFactory.getLogger(StrategyTuningDailyJob.class);
    private final StrategyTuningService strategyTuningService;

    public StrategyTuningDailyJob(StrategyTuningService strategyTuningService) {
        this.strategyTuningService = strategyTuningService;
    }

    @Scheduled(cron = "0 0 19 ? * FRI", zone = "Asia/Taipei")
    public void generateWeeklyRecommendations() {
        var generated = strategyTuningService.generateDailyRecommendations();
        log.info("[StrategyTuningDailyJob] generated {} PENDING recommendations", generated.size());
    }
}
