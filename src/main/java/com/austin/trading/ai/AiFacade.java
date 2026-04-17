package com.austin.trading.ai;

import com.austin.trading.ai.prompt.FinalDecisionPromptBuilder;
import com.austin.trading.ai.prompt.HourlyGatePromptBuilder;
import com.austin.trading.ai.prompt.PremarketPromptBuilder;
import com.austin.trading.ai.prompt.StockEvaluationPromptBuilder;
import com.austin.trading.dto.response.AiResearchResponse;
import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.dto.response.FinalDecisionRecordResponse;
import com.austin.trading.dto.response.FinalDecisionResponse;
import com.austin.trading.dto.response.HourlyGateDecisionRecordResponse;
import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.dto.response.PositionResponse;
import com.austin.trading.service.AiResearchService;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.FinalDecisionService;
import com.austin.trading.service.HourlyGateDecisionService;
import com.austin.trading.service.MarketDataService;
import com.austin.trading.service.PositionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * AI 高階門面。
 * <p>
 * 收集系統資料 → 建構 Prompt → 呼叫 {@link AiResearchService} 執行研究。
 * Scheduler 或 API Controller 透過此門面呼叫 AI 功能。
 * </p>
 */
@Component
public class AiFacade {

    private static final Logger log = LoggerFactory.getLogger(AiFacade.class);

    private final AiResearchService         aiResearchService;
    private final MarketDataService         marketDataService;
    private final CandidateScanService      candidateScanService;
    private final FinalDecisionService      finalDecisionService;
    private final HourlyGateDecisionService hourlyGateDecisionService;
    private final PositionService           positionService;

    public AiFacade(
            AiResearchService aiResearchService,
            MarketDataService marketDataService,
            CandidateScanService candidateScanService,
            FinalDecisionService finalDecisionService,
            HourlyGateDecisionService hourlyGateDecisionService,
            PositionService positionService
    ) {
        this.aiResearchService         = aiResearchService;
        this.marketDataService         = marketDataService;
        this.candidateScanService      = candidateScanService;
        this.finalDecisionService      = finalDecisionService;
        this.hourlyGateDecisionService = hourlyGateDecisionService;
        this.positionService           = positionService;
    }

    // ── 08:30 盤前研究 ─────────────────────────────────────────────────────────

    /**
     * 執行 08:30 盤前研究。
     * 讀取昨日候選股 + 昨日市場狀態，呼叫 Claude 分析今日開盤策略。
     */
    public AiResearchResponse doPremarketResearch(LocalDate date, String txfSummary, String globalSummary) {
        MarketCurrentResponse market = marketDataService.getCurrentMarket().orElse(null);
        List<CandidateResponse> candidates = candidateScanService.getCandidatesByDate(date.minusDays(1), 10);

        String prompt = PremarketPromptBuilder.build(date, market, candidates, txfSummary, globalSummary);
        String title  = date + " 08:30 盤前研究";

        return aiResearchService.research(
                date, "PREMARKET", null, prompt,
                PremarketPromptBuilder.SYSTEM_CONTEXT, title
        );
    }

    // ── 個股深度研究 ───────────────────────────────────────────────────────────

    /**
     * 執行個股深度研究。
     *
     * @param date   交易日
     * @param symbol 個股代號
     */
    public AiResearchResponse doStockEvaluation(LocalDate date, String symbol) {
        MarketCurrentResponse market = marketDataService.getCurrentMarket().orElse(null);
        List<CandidateResponse> candidates = candidateScanService.getCandidatesByDate(date, 20);

        CandidateResponse candidate = candidates.stream()
                .filter(c -> symbol.equals(c.symbol()))
                .findFirst()
                .orElse(null);

        if (candidate == null) {
            log.warn("[AiFacade] StockEval: symbol {} not in candidates for {}", symbol, date);
            // 還是可以做研究，只是沒有候選股資料
        }

        String payload = candidate != null
                ? null  // entryPriceZone 等已包含在 CandidateResponse
                : null;

        String prompt = StockEvaluationPromptBuilder.build(
                date,
                candidate != null ? candidate : dummyCandidate(symbol),
                market, payload
        );
        String title = date + " " + symbol + " 個股深度研究";

        return aiResearchService.research(
                date, "STOCK_EVAL", symbol, prompt,
                StockEvaluationPromptBuilder.SYSTEM_CONTEXT, title
        );
    }

    // ── 09:30 最終決策前確認 ───────────────────────────────────────────────────

    /**
     * 執行 09:30 最終決策前研究確認。
     */
    public AiResearchResponse doFinalDecisionResearch(LocalDate date) {
        MarketCurrentResponse market = marketDataService.getCurrentMarket().orElse(null);
        // getCurrent() 返回 FinalDecisionRecordResponse（DB 紀錄版），FinalDecisionResponse 需重新評估
        // 此處傳 null 讓 FinalDecisionPromptBuilder 跳過引擎決策欄位
        List<CandidateResponse> candidates = candidateScanService.getCurrentCandidates(10);
        List<PositionResponse> openPositions = positionService.getOpenPositions(20);

        String prompt = FinalDecisionPromptBuilder.build(
                date, market, null, candidates, openPositions
        );
        String title = date + " 09:30 最終決策研究";

        return aiResearchService.research(
                date, "FINAL_DECISION", null, prompt,
                FinalDecisionPromptBuilder.SYSTEM_CONTEXT, title
        );
    }

    // ── 整點行情閘研究 ─────────────────────────────────────────────────────────

    /**
     * 執行整點行情閘研究。
     */
    public AiResearchResponse doHourlyGateResearch(LocalDate date, LocalTime time) {
        MarketCurrentResponse market = marketDataService.getCurrentMarket().orElse(null);
        hourlyGateDecisionService.getCurrent(); // gateRecord available if needed
        List<PositionResponse> openPositions = positionService.getOpenPositions(20);

        // HourlyGatePromptBuilder 接受完整 HourlyGateDecisionResponse，
        // 從 DB 記錄中只有部分欄位，傳 null 讓 builder 跳過 gate detail
        String prompt = HourlyGatePromptBuilder.build(
                date, time, market, null, openPositions
        );
        String title = date + " " + time + " 整點行情閘研究";

        return aiResearchService.research(
                date, "HOURLY_GATE", null, prompt,
                HourlyGatePromptBuilder.SYSTEM_CONTEXT, null // 整點研究不主動發布
        );
    }

    // ── 私有工具 ──────────────────────────────────────────────────────────────────

    /** 當候選清單中找不到該股時，使用最小佔位符 */
    private CandidateResponse dummyCandidate(String symbol) {
        return new CandidateResponse(null, symbol, null, null, null, null, null, null, null);
    }
}
