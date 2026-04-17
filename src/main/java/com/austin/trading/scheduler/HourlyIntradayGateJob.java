package com.austin.trading.scheduler;

import com.austin.trading.dto.request.HourlyGateEvaluateRequest;
import com.austin.trading.dto.request.TradingStateUpsertRequest;
import com.austin.trading.dto.response.HourlyGateDecisionResponse;
import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.dto.response.TradingStateResponse;
import com.austin.trading.engine.HourlyGateEngine;
import com.austin.trading.notify.LineTemplateService;
import com.austin.trading.service.HourlyGateDecisionService;
import com.austin.trading.service.MarketDataService;
import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.service.TradingStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "trading.scheduler.hourly-gate", name = "enabled", havingValue = "true")
public class HourlyIntradayGateJob {

    private static final Logger log = LoggerFactory.getLogger(HourlyIntradayGateJob.class);

    private final MarketDataService marketDataService;
    private final TradingStateService tradingStateService;
    private final HourlyGateDecisionService hourlyGateDecisionService;
    private final HourlyGateEngine hourlyGateEngine;
    private final LineTemplateService lineTemplateService;
    private final SchedulerLogService schedulerLogService;

    public HourlyIntradayGateJob(
            MarketDataService marketDataService,
            TradingStateService tradingStateService,
            HourlyGateDecisionService hourlyGateDecisionService,
            HourlyGateEngine hourlyGateEngine,
            LineTemplateService lineTemplateService,
            SchedulerLogService schedulerLogService
    ) {
        this.marketDataService = marketDataService;
        this.tradingStateService = tradingStateService;
        this.hourlyGateDecisionService = hourlyGateDecisionService;
        this.hourlyGateEngine = hourlyGateEngine;
        this.lineTemplateService = lineTemplateService;
        this.schedulerLogService = schedulerLogService;
    }

    @Scheduled(cron = "${trading.scheduler.hourly-gate-cron:0 5 10-13 * * MON-FRI}", zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "HourlyIntradayGateJob";
        Optional<MarketCurrentResponse> currentMarket = marketDataService.getCurrentMarket();
        if (currentMarket.isEmpty()) {
            log.info("[HourlyIntradayGateJob] Skip: no market snapshot available.");
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), "Skip: no market snapshot.");
            return;
        }

        try {
            MarketCurrentResponse market = currentMarket.get();
            TradingStateResponse previousState = tradingStateService.getCurrentState().orElse(null);

            HourlyGateEvaluateRequest request = new HourlyGateEvaluateRequest(
                    safe(market.marketGrade(), "B"),
                    safe(market.decision(), "WATCH"),
                    previousState == null ? null : previousState.marketGrade(),
                    previousState == null ? null : "WATCH",
                    previousState == null ? null : previousState.hourlyGate(),
                    previousState == null ? null : previousState.decisionLock(),
                    null,
                    LocalTime.now(),
                    false,
                    true,
                    false
            );

            HourlyGateDecisionResponse result = hourlyGateEngine.evaluate(request);
            hourlyGateDecisionService.save(LocalDate.now(), LocalTime.now(), result);

            tradingStateService.create(new TradingStateUpsertRequest(
                    LocalDate.now(),
                    result.marketGrade(),
                    result.decisionLock(),
                    result.timeDecayStage(),
                    result.hourlyGate(),
                    result.shouldRun5mMonitor() ? "WATCH" : "OFF",
                    toPayload(result)
            ));

            lineTemplateService.notifyHourlyGate(result, LocalTime.now());

            log.info("[HourlyIntradayGateJob] {}", result.summaryForLog());
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), result.summaryForLog());
        } catch (Exception e) {
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }

    private String toPayload(HourlyGateDecisionResponse result) {
        return "{" +
                "\"market_grade\":\"" + result.marketGrade() + "\"," +
                "\"market_phase\":\"" + result.marketPhase() + "\"," +
                "\"decision\":\"" + result.decision() + "\"," +
                "\"hourly_gate\":\"" + result.hourlyGate() + "\"," +
                "\"should_run_5m_monitor\":" + result.shouldRun5mMonitor() + "," +
                "\"should_notify\":" + result.shouldNotify() + "," +
                "\"trigger_event\":\"" + result.triggerEvent() + "\"," +
                "\"decision_lock\":\"" + result.decisionLock() + "\"," +
                "\"time_decay_stage\":\"" + result.timeDecayStage() + "\"" +
                "}";
    }

    private String safe(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
