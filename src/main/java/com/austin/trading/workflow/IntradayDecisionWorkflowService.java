package com.austin.trading.workflow;

import com.austin.trading.service.FinalDecisionService;
import com.austin.trading.service.MarketDataService;
import com.austin.trading.service.ScoreConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 09:30 最終決策工作流編排器。
 *
 * <pre>
 * Step 1: 確認市場等級（若 C 直接輸出休息）
 * Step 2: VetoEngine 批次淘汰候選股
 * Step 3: WeightedScoringEngine 計算加權排名
 * Step 4: FinalDecisionEngine 輸出最終名單（依 decision.final.maxCount）
 * Step 5: Java 發 LINE 通知（scheduling.line_notify_enabled = true 時）
 * </pre>
 */
@Service
public class IntradayDecisionWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(IntradayDecisionWorkflowService.class);

    private final MarketDataService marketDataService;
    private final FinalDecisionService finalDecisionService;
    private final ScoreConfigService config;

    public IntradayDecisionWorkflowService(
            MarketDataService marketDataService,
            FinalDecisionService finalDecisionService,
            ScoreConfigService config
    ) {
        this.marketDataService = marketDataService;
        this.finalDecisionService = finalDecisionService;
        this.config = config;
    }

    /**
     * 執行 09:30 最終決策流程。
     */
    public void execute(LocalDate tradingDate) {
        log.info("[IntradayDecisionWorkflow] 開始 tradingDate={}", tradingDate);

        // Step 1: 市場等級確認
        var marketOpt = marketDataService.getCurrentMarket();
        if (marketOpt.isEmpty()) {
            log.warn("[IntradayDecisionWorkflow] 無市場快照，以預設 B 級執行");
        }

        // Step 2~4: FinalDecisionService.evaluateAndPersist 已整合 Veto + 評分 + Final
        // Phase 2 將在此加入 VetoEngine.batchEvaluate + WeightedScoringEngine.computeRanking
        var result = finalDecisionService.evaluateAndPersist(tradingDate);
        log.info("[IntradayDecisionWorkflow] 決策={}，入選={} 檔",
                result.decision(), result.selectedStocks().size());

        // Step 5: LINE 通知
        boolean lineEnabled = config.getBoolean("scheduling.line_notify_enabled", false);
        if (lineEnabled) {
            // TODO Phase 3: lineNotifyService.sendFinalDecision(result)
            log.info("[IntradayDecisionWorkflow] LINE 通知已設定啟用（TODO Phase 3）");
        }
    }
}
