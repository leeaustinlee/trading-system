package com.austin.trading.scheduler;

import com.austin.trading.dto.request.MonitorEvaluateRequest;
import com.austin.trading.dto.request.TradingStateUpsertRequest;
import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.dto.response.MonitorDecisionResponse;
import com.austin.trading.dto.response.TradingStateResponse;
import com.austin.trading.engine.MonitorDecisionEngine;
import com.austin.trading.notify.LineTemplateService;
import com.austin.trading.service.MarketDataService;
import com.austin.trading.service.MonitorDecisionService;
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


@Component
@ConditionalOnProperty(prefix = "trading.scheduler.five-minute-monitor", name = "enabled", havingValue = "true")
public class FiveMinuteMonitorJob {

    private static final Logger log = LoggerFactory.getLogger(FiveMinuteMonitorJob.class);

    private final MarketDataService marketDataService;
    private final TradingStateService tradingStateService;
    private final MonitorDecisionEngine monitorDecisionEngine;
    private final MonitorDecisionService monitorDecisionService;
    private final LineTemplateService lineTemplateService;
    private final SchedulerLogService schedulerLogService;

    public FiveMinuteMonitorJob(
            MarketDataService marketDataService,
            TradingStateService tradingStateService,
            MonitorDecisionEngine monitorDecisionEngine,
            MonitorDecisionService monitorDecisionService,
            LineTemplateService lineTemplateService,
            SchedulerLogService schedulerLogService
    ) {
        this.marketDataService = marketDataService;
        this.tradingStateService = tradingStateService;
        this.monitorDecisionEngine = monitorDecisionEngine;
        this.monitorDecisionService = monitorDecisionService;
        this.lineTemplateService = lineTemplateService;
        this.schedulerLogService = schedulerLogService;
    }

    @Scheduled(cron = "${trading.scheduler.five-minute-monitor-cron:0 */5 9-13 * * MON-FRI}", zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "FiveMinuteMonitorJob";
        try {
            MarketCurrentResponse market = marketDataService.getCurrentMarket().orElse(null);
            if (market == null) {
                schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), "Skip: no market snapshot.");
                return;
            }

            TradingStateResponse state = tradingStateService.getCurrentState().orElse(null);

            MonitorEvaluateRequest request = new MonitorEvaluateRequest(
                    safe(market.marketGrade(), "B"),
                    safe(market.decision(), "WATCH"),
                    market.marketPhase(),
                    state == null ? null : state.monitorMode(),
                    null,
                    LocalTime.now(),
                    false,
                    true,
                    false,
                    state == null ? "NONE" : state.decisionLock(),
                    state == null ? null : state.timeDecayStage()
            );

            MonitorDecisionResponse decision = monitorDecisionEngine.evaluate(request);
            monitorDecisionService.save(LocalDate.now(), LocalDateTime.now(), decision);

            tradingStateService.create(new TradingStateUpsertRequest(
                    LocalDate.now(),
                    decision.marketGrade(),
                    decision.decisionLock(),
                    decision.timeDecayStage(),
                    state == null ? "ON" : safe(state.hourlyGate(), "ON"),
                    decision.monitorMode(),
                    toPayload(decision)
            ));

            lineTemplateService.notifyMonitor(decision, LocalTime.now());

            log.info("[FiveMinuteMonitorJob] {}", decision.summaryForLog());
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), decision.summaryForLog());
        } catch (Exception e) {
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }

    private String toPayload(MonitorDecisionResponse decision) {
        return "{" +
                "\"market_grade\":\"" + decision.marketGrade() + "\"," +
                "\"market_phase\":\"" + decision.marketPhase() + "\"," +
                "\"decision\":\"" + decision.decision() + "\"," +
                "\"monitor_mode\":\"" + decision.monitorMode() + "\"," +
                "\"should_notify\":" + decision.shouldNotify() + "," +
                "\"trigger_event\":\"" + decision.triggerEvent() + "\"," +
                "\"decision_lock\":\"" + decision.decisionLock() + "\"," +
                "\"time_decay_stage\":\"" + decision.timeDecayStage() + "\"" +
                "}";
    }

    private String safe(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
