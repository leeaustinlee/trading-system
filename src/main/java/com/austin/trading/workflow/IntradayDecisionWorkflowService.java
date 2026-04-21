package com.austin.trading.workflow;

import com.austin.trading.dto.response.FinalDecisionResponse;
import com.austin.trading.notify.LineTemplateService;
import com.austin.trading.service.AiTaskService;
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

    private final MarketDataService    marketDataService;
    private final FinalDecisionService finalDecisionService;
    private final LineTemplateService  lineTemplateService;
    private final ScoreConfigService   config;
    private final AiTaskService        aiTaskService;

    public IntradayDecisionWorkflowService(
            MarketDataService marketDataService,
            FinalDecisionService finalDecisionService,
            LineTemplateService lineTemplateService,
            ScoreConfigService config,
            AiTaskService aiTaskService
    ) {
        this.marketDataService    = marketDataService;
        this.finalDecisionService = finalDecisionService;
        this.lineTemplateService  = lineTemplateService;
        this.config               = config;
        this.aiTaskService        = aiTaskService;
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

        // Step 2~4: 分層管線 — Regime → Theme → Ranking → Setup → Timing → Risk → Execution
        log.info("[IntradayDecisionWorkflow] 啟動分層管線：Regime → Theme → Ranking → Setup → Timing → Risk → Execution");
        var result = finalDecisionService.evaluateAndPersist(tradingDate);
        log.info("[IntradayDecisionWorkflow] 管線完成 — 決策={} 入選={} 檔",
                result.decision(), result.selectedStocks().size());

        // Step 5: LINE 通知（由 scheduling.line_notify_enabled 控制）
        boolean lineEnabled = config.getBoolean("scheduling.line_notify_enabled", false);
        if (lineEnabled) {
            lineTemplateService.notifyFinalDecision(result, tradingDate);
            log.info("[IntradayDecisionWorkflow] LINE 最終決策已發送");

            // 額外：若有 Claude / Codex 研究 markdown，補發一則「AI 研究摘要」LINE
            String aiMd = aiTaskService.findLatestMarkdown(tradingDate, "OPENING", "PREMARKET");
            if (aiMd != null && aiMd.length() > 100) {
                String summary = aiMd.length() > 3500
                        ? aiMd.substring(0, 3500) + "\n...(內容過長已截斷，詳見 claude-research-latest.md)"
                        : aiMd;
                lineTemplateService.notifySystemAlert("📎 09:30 AI 研究摘要", summary);
                log.info("[IntradayDecisionWorkflow] LINE AI 研究摘要已補發 ({} chars)", aiMd.length());
            } else {
                log.info("[IntradayDecisionWorkflow] 無可用 AI 研究 md 補發");
            }
        } else {
            log.info("[IntradayDecisionWorkflow] LINE 通知未啟用（scheduling.line_notify_enabled=false）");
        }
    }


    /** 供外部直接取得結果（不重複計算）的入口 */
    public FinalDecisionResponse evaluateAndPersist(LocalDate tradingDate) {
        return finalDecisionService.evaluateAndPersist(tradingDate);
    }
}
