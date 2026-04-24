package com.austin.trading.scheduler;

import com.austin.trading.dto.request.MonitorEvaluateRequest;
import com.austin.trading.dto.request.TradingStateUpsertRequest;
import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.dto.response.MonitorDecisionResponse;
import com.austin.trading.dto.response.TradingStateResponse;
import com.austin.trading.dto.internal.CapitalAllocationResult;
import com.austin.trading.dto.internal.PositionManagementResult;
import com.austin.trading.engine.MonitorDecisionEngine;
import com.austin.trading.engine.PositionDecisionEngine.PositionDecisionResult;
import com.austin.trading.engine.PositionDecisionEngine.PositionStatus;
import com.austin.trading.notify.LineTemplateService;
import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.MarketDataService;
import com.austin.trading.service.MonitorDecisionService;
import com.austin.trading.service.OrchestrationStep;
import com.austin.trading.service.PositionManagementService;
import com.austin.trading.service.PositionReviewService;
import com.austin.trading.service.PositionReviewService.ReviewResult;
import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.service.ScoreConfigService;
import com.austin.trading.service.TradingStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;


@Component
@ConditionalOnProperty(prefix = "trading.scheduler.five-minute-monitor", name = "enabled", havingValue = "true")
public class FiveMinuteMonitorJob {

    private static final Logger log = LoggerFactory.getLogger(FiveMinuteMonitorJob.class);

    private final MarketDataService marketDataService;
    private final TradingStateService tradingStateService;
    private final MonitorDecisionEngine monitorDecisionEngine;
    private final MonitorDecisionService monitorDecisionService;
    private final PositionReviewService positionReviewService;
    private final PositionManagementService positionManagementService;
    private final LineTemplateService lineTemplateService;
    private final SchedulerLogService schedulerLogService;
    private final ScoreConfigService scoreConfig;
    private final DailyOrchestrationService orchestrationService;

    public FiveMinuteMonitorJob(
            MarketDataService marketDataService,
            TradingStateService tradingStateService,
            MonitorDecisionEngine monitorDecisionEngine,
            MonitorDecisionService monitorDecisionService,
            PositionReviewService positionReviewService,
            PositionManagementService positionManagementService,
            LineTemplateService lineTemplateService,
            SchedulerLogService schedulerLogService,
            ScoreConfigService scoreConfig,
            DailyOrchestrationService orchestrationService
    ) {
        this.marketDataService = marketDataService;
        this.tradingStateService = tradingStateService;
        this.monitorDecisionEngine = monitorDecisionEngine;
        this.monitorDecisionService = monitorDecisionService;
        this.positionReviewService = positionReviewService;
        this.positionManagementService = positionManagementService;
        this.lineTemplateService = lineTemplateService;
        this.schedulerLogService = schedulerLogService;
        this.scoreConfig = scoreConfig;
        this.orchestrationService = orchestrationService;
    }

    @Scheduled(cron = "${trading.scheduler.five-minute-monitor-cron:0 */5 9-13 * * MON-FRI}", zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "FiveMinuteMonitorJob";
        LocalDate today = LocalDate.now();
        OrchestrationStep step = OrchestrationStep.FIVE_MINUTE_MONITOR;
        // 一天跑多次：不做 DONE 阻擋，完成後 markExecuted 更新 updated_at
        try {
            MarketCurrentResponse market = marketDataService.getCurrentMarket().orElse(null);
            if (market == null) {
                schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), "Skip: no market snapshot.");
                orchestrationService.markExecuted(today, step, "Skip: no market snapshot.");
                return;
            }

            // v2.4：只讀今日 state，避免跨日污染（例如前日 LATE/LOCKED 被沿用到今日 09:05）
            TradingStateResponse state = tradingStateService.getTodayState().orElse(null);

            // v2.4：timeDecayStage 一律由 MonitorDecisionEngine 依 evaluationTime 重算，
            //       這裡不再把可能 stale 的 state.timeDecayStage() 塞進 request。
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
                    state == null ? "NONE" : safe(state.decisionLock(), "NONE"),
                    null  // 顯式傳 null，engine 會以 LocalTime.now() 重算
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

            // ── 持倉監控 ───────────────────────────────────────────────
            // monitorMode=OFF 時只寫 review log，不發 LINE（避免無事件噪音）
            try {
                var reviews = positionReviewService.reviewAllOpenPositions("INTRADAY");
                boolean lineEnabled = scoreConfig.getBoolean("scheduling.line_notify_enabled", false);
                String monitorMode = decision.monitorMode() == null ? "OFF" : decision.monitorMode();
                boolean monitorActive = !"OFF".equalsIgnoreCase(monitorMode);

                for (ReviewResult r : reviews) {
                    PositionStatus s = r.decision().status();
                    boolean isActionable =
                            s == PositionStatus.WEAKEN || s == PositionStatus.EXIT || s == PositionStatus.TRAIL_UP;
                    // 即時報價不可用 → 只記 review log 不發 LINE（由 LineTemplateService 二次把關）
                    boolean hasValidQuote = r.currentPrice() != null;
                    if (lineEnabled && monitorActive && isActionable && hasValidQuote) {
                        lineTemplateService.notifyPositionAlert(
                                r.position().getSymbol(), s.name(), r.decision().reason(),
                                r.currentPrice().doubleValue(),
                                r.position().getAvgCost() != null ? r.position().getAvgCost().doubleValue() : null,
                                r.pnlPct() != null ? r.pnlPct().doubleValue() : null);
                    }
                }

                // v2.10 Position Management + v2.11 Capital Allocation：
                //   ADD / REDUCE / SWITCH_HINT / EXIT 提示（HOLD 不發 LINE）+ 附建議金額 / 股數 / 減碼比例
                try {
                    List<PositionManagementResult> mgmtResults =
                            positionManagementService.evaluateAll(reviews, LocalDate.now());
                    // index reviews by symbol for position lookup
                    java.util.Map<String, ReviewResult> reviewBySymbol = new java.util.HashMap<>();
                    for (ReviewResult rv : reviews) {
                        if (rv.position() != null) reviewBySymbol.put(rv.position().getSymbol(), rv);
                    }
                    for (PositionManagementResult mgmt : mgmtResults) {
                        if (!mgmt.requiresNotification()) continue;
                        if (!lineEnabled || !monitorActive) continue;
                        if (mgmt.currentPrice() == null) continue;

                        Double switchGap = null;
                        String switchTo = null;
                        Object switchHint = mgmt.trace().get("switchHint");
                        if (switchHint instanceof java.util.Map<?, ?> map) {
                            Object to  = map.get("switchTo");
                            Object gap = map.get("scoreGap");
                            switchTo = to == null ? null : String.valueOf(to);
                            if (gap instanceof Number n) switchGap = n.doubleValue();
                        }

                        // Resolve allocation for non-EXIT actions
                        CapitalAllocationResult alloc = null;
                        ReviewResult rv = reviewBySymbol.get(mgmt.symbol());
                        if (rv != null && rv.position() != null) {
                            String regimeAtEvaluator = (String) mgmt.trace().get("marketRegime");
                            alloc = positionManagementService.resolveAllocation(mgmt, rv.position(), regimeAtEvaluator);
                        }
                        Double allocAmount = alloc != null && alloc.suggestedAmount() != null
                                ? alloc.suggestedAmount().doubleValue() : null;
                        Integer allocShares = alloc != null ? alloc.suggestedShares() : null;
                        Double reducePct = alloc != null && alloc.suggestedReducePct() != null
                                ? alloc.suggestedReducePct().doubleValue() : null;

                        lineTemplateService.notifyPositionAction(
                                mgmt.symbol(), mgmt.action().name(), mgmt.reason(),
                                mgmt.currentPrice().doubleValue(),
                                mgmt.entryPrice() == null ? null : mgmt.entryPrice().doubleValue(),
                                mgmt.unrealizedPct() == null ? null : mgmt.unrealizedPct().doubleValue(),
                                mgmt.signals(), switchTo, switchGap,
                                allocAmount, allocShares, reducePct);
                    }
                } catch (Exception mgmtE) {
                    log.warn("[FiveMinuteMonitorJob] PositionManagement 失敗: {}", mgmtE.getMessage());
                }

                log.info("[FiveMinuteMonitorJob] 持倉監控完成，共 {} 筆 (monitorMode={}, lineEnabled={})",
                        reviews.size(), monitorMode, lineEnabled);
            } catch (Exception pe) {
                log.warn("[FiveMinuteMonitorJob] 持倉監控失敗: {}", pe.getMessage());
            }

            log.info("[FiveMinuteMonitorJob] {}", decision.summaryForLog());
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), decision.summaryForLog());
            orchestrationService.markExecuted(today, step, decision.summaryForLog());
        } catch (Exception e) {
            orchestrationService.markFailed(today, step, e.getMessage());
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
