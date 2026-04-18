package com.austin.trading.workflow;

import com.austin.trading.engine.HourlyGateEngine;
import com.austin.trading.service.HourlyGateDecisionService;
import com.austin.trading.service.MarketDataService;
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
 * Step 1: 讀取目前市場等級 + 交易狀態
 * Step 2: HourlyGateEngine.evaluate
 * Step 3: 寫入 DB
 * Step 4: 若 should_notify → LINE 通知（Phase 3）
 * </pre>
 */
@Service
public class HourlyGateWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(HourlyGateWorkflowService.class);

    private final MarketDataService marketDataService;
    private final TradingStateService tradingStateService;
    private final HourlyGateEngine hourlyGateEngine;
    private final HourlyGateDecisionService hourlyGateDecisionService;

    public HourlyGateWorkflowService(
            MarketDataService marketDataService,
            TradingStateService tradingStateService,
            HourlyGateEngine hourlyGateEngine,
            HourlyGateDecisionService hourlyGateDecisionService
    ) {
        this.marketDataService = marketDataService;
        this.tradingStateService = tradingStateService;
        this.hourlyGateEngine = hourlyGateEngine;
        this.hourlyGateDecisionService = hourlyGateDecisionService;
    }

    public void execute(LocalDate tradingDate, LocalTime gateTime) {
        log.info("[HourlyGateWorkflow] 開始 date={} time={}", tradingDate, gateTime);

        // Step 1 + 2: HourlyGateIntradayGateJob 已有實作，此處為 Phase 2 重構目標
        // TODO Phase 2: 將 HourlyIntradayGateJob 的邏輯移入此 Workflow

        log.info("[HourlyGateWorkflow] (TODO Phase 2: 整合 HourlyGateEngine 呼叫至此 Workflow)");
    }
}
