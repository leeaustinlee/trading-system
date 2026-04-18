package com.austin.trading.workflow;

import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.ClaudeCodeRequestWriterService;
import com.austin.trading.service.MarketDataService;
import com.austin.trading.service.ScoreConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 盤前工作流編排器（08:10 觸發）。
 *
 * <p>Scheduler 只負責呼叫 {@link #execute}，所有商業邏輯在此編排。</p>
 *
 * <pre>
 * Step 1: 建立 PREMARKET 市場快照
 * Step 2: 題材掃描（ThemeSelectionEngine，Phase 2 完整實作）
 * Step 3: 候選股初篩（依 candidate.scan.maxCount）
 * Step 4: Java 結構評分（StockEvaluationEngine，Phase 2）
 * Step 5: 寫出 Claude 研究請求（依 candidate.research.maxCount）
 * </pre>
 */
@Service
public class PremarketWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(PremarketWorkflowService.class);

    private final MarketDataService marketDataService;
    private final CandidateScanService candidateScanService;
    private final ClaudeCodeRequestWriterService requestWriterService;
    private final ScoreConfigService config;

    public PremarketWorkflowService(
            MarketDataService marketDataService,
            CandidateScanService candidateScanService,
            ClaudeCodeRequestWriterService requestWriterService,
            ScoreConfigService config
    ) {
        this.marketDataService = marketDataService;
        this.candidateScanService = candidateScanService;
        this.requestWriterService = requestWriterService;
        this.config = config;
    }

    /**
     * 執行完整盤前流程。
     *
     * @param tradingDate 交易日（通常為 LocalDate.now()）
     */
    public void execute(LocalDate tradingDate) {
        log.info("[PremarketWorkflow] 開始 tradingDate={}", tradingDate);

        // Step 1: 市場快照已由 PremarketDataPrepJob 建立，此處確認存在
        boolean hasMarket = marketDataService.getCurrentMarket().isPresent();
        if (!hasMarket) {
            log.warn("[PremarketWorkflow] 無市場快照，跳過後續步驟");
            return;
        }

        // Step 2: TODO Phase 2 - ThemeSelectionEngine.computeMarketBehaviorScores(tradingDate)

        // Step 3: 取得目前候選股（由 Codex 或手動寫入的，或 Phase 2 自動掃描）
        int scanMax = config.getInt("candidate.scan.maxCount", 10);
        var candidates = candidateScanService.getCurrentCandidates(scanMax);
        log.info("[PremarketWorkflow] 取得候選股 {} 檔", candidates.size());

        // Step 4: TODO Phase 2 - StockEvaluationEngine.batchComputeStructureScore(candidates)
        // Step 4: TODO Phase 2 - VetoEngine.batchEvaluate(candidates)
        // Step 4: TODO Phase 2 - WeightedScoringEngine.computeRanking(candidates)

        // Step 5: 寫出 Claude 研究請求
        int researchMax = config.getInt("candidate.research.maxCount", 5);
        List<String> topSymbols = candidates.stream()
                .limit(researchMax)
                .map(c -> c.symbol())
                .toList();

        boolean written = requestWriterService.writeRequest("PREMARKET", tradingDate, topSymbols, null);
        log.info("[PremarketWorkflow] Claude 研究請求寫出={}, symbols={}", written, topSymbols);
    }
}
