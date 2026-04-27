package com.austin.trading.workflow;

import com.austin.trading.dto.request.HourlyGateEvaluateRequest;
import com.austin.trading.dto.request.TradingStateUpsertRequest;
import com.austin.trading.dto.response.HourlyGateDecisionResponse;
import com.austin.trading.dto.response.TradingStateResponse;
import com.austin.trading.engine.HourlyGateEngine;
import com.austin.trading.notify.NotificationFacade;
import com.austin.trading.service.HourlyGateDecisionService;
import com.austin.trading.service.MarketDataService;
import com.austin.trading.service.ScoreConfigService;
import com.austin.trading.service.TradingStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 整點閘道工作流編排器（10:05 / 11:05 / 12:05 / 13:05 觸發）。
 *
 * <pre>
 * Step 1: 讀取目前市場等級 + 前次交易狀態
 * Step 2: HourlyGateEngine.evaluate
 * Step 3: 寫入 hourly_gate_decision + trading_state
 * Step 4: 若 should_notify 且 scheduling.line_notify_enabled → LINE 通知
 * </pre>
 */
@Service
public class HourlyGateWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(HourlyGateWorkflowService.class);

    private final MarketDataService        marketDataService;
    private final TradingStateService      tradingStateService;
    private final HourlyGateEngine         hourlyGateEngine;
    private final HourlyGateDecisionService hourlyGateDecisionService;
    private final NotificationFacade      notificationFacade;
    private final ScoreConfigService       config;

    public HourlyGateWorkflowService(
            MarketDataService marketDataService,
            TradingStateService tradingStateService,
            HourlyGateEngine hourlyGateEngine,
            HourlyGateDecisionService hourlyGateDecisionService,
            NotificationFacade notificationFacade,
            ScoreConfigService config
    ) {
        this.marketDataService       = marketDataService;
        this.tradingStateService     = tradingStateService;
        this.hourlyGateEngine        = hourlyGateEngine;
        this.hourlyGateDecisionService = hourlyGateDecisionService;
        this.notificationFacade     = notificationFacade;
        this.config                  = config;
    }

    /**
     * 執行整點閘道評估流程。
     *
     * @param tradingDate 交易日
     * @param gateTime    閘道觸發時間（10:05 / 11:05 / 12:05 / 13:05）
     */
    public void execute(LocalDate tradingDate, LocalTime gateTime) {
        log.info("[HourlyGateWorkflow] 開始 date={} time={}", tradingDate, gateTime);

        // Step 1: 確認市場快照存在
        var marketOpt = marketDataService.getCurrentMarket();
        if (marketOpt.isEmpty()) {
            log.info("[HourlyGateWorkflow] Skip: 無市場快照");
            return;
        }

        var market = marketOpt.get();
        // v2.4：只讀今日 state，避免跨日污染
        TradingStateResponse previousState = tradingStateService.getStateByDate(tradingDate).orElse(null);

        // Step 2: 組裝請求並執行 HourlyGateEngine
        HourlyGateEvaluateRequest request = new HourlyGateEvaluateRequest(
                safe(market.marketGrade(), "B"),
                safe(market.decision(), "WATCH"),
                previousState == null ? null : previousState.marketGrade(),
                previousState == null ? null : "WATCH",
                previousState == null ? null : previousState.hourlyGate(),
                previousState == null ? null : previousState.decisionLock(),
                null,
                gateTime,
                false,
                true,
                false
        );

        HourlyGateDecisionResponse result = hourlyGateEngine.evaluate(request);

        // Step 3: 寫入 DB
        hourlyGateDecisionService.save(tradingDate, gateTime, result);

        tradingStateService.create(new TradingStateUpsertRequest(
                tradingDate,
                result.marketGrade(),
                result.decisionLock(),
                result.timeDecayStage(),
                result.hourlyGate(),
                result.shouldRun5mMonitor() ? "WATCH" : "OFF",
                buildPayload(result)
        ));

        log.info("[HourlyGateWorkflow] {}", result.summaryForLog());

        // Step 4: LINE 通知（由 scheduling.line_notify_enabled 控制）
        boolean lineEnabled = config.getBoolean("scheduling.line_notify_enabled", false);
        if (lineEnabled && result.shouldNotify()) {
            notificationFacade.notifyHourlyGate(result, gateTime);
            log.info("[HourlyGateWorkflow] LINE 通知已發送");
        } else if (!lineEnabled) {
            log.info("[HourlyGateWorkflow] LINE 通知未啟用（scheduling.line_notify_enabled=false）");
        }
    }

    // ── 私有方法 ────────────────────────────────────────────────────────────

    private String buildPayload(HourlyGateDecisionResponse r) {
        return "{" +
                "\"market_grade\":\"" + r.marketGrade() + "\"," +
                "\"market_phase\":\"" + r.marketPhase() + "\"," +
                "\"decision\":\"" + r.decision() + "\"," +
                "\"hourly_gate\":\"" + r.hourlyGate() + "\"," +
                "\"should_run_5m_monitor\":" + r.shouldRun5mMonitor() + "," +
                "\"should_notify\":" + r.shouldNotify() + "," +
                "\"trigger_event\":\"" + r.triggerEvent() + "\"," +
                "\"decision_lock\":\"" + r.decisionLock() + "\"," +
                "\"time_decay_stage\":\"" + r.timeDecayStage() + "\"" +
                "}";
    }

    private String safe(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
