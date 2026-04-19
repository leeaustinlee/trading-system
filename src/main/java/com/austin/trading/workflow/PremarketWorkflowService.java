package com.austin.trading.workflow;

import com.austin.trading.dto.request.AiTaskCandidateRef;
import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.engine.JavaStructureScoringEngine;
import com.austin.trading.engine.JavaStructureScoringEngine.JavaStructureInput;
import com.austin.trading.engine.ThemeSelectionEngine;
import com.austin.trading.entity.StockEvaluationEntity;
import com.austin.trading.entity.ThemeSnapshotEntity;
import com.austin.trading.notify.LineTemplateService;
import com.austin.trading.repository.StockEvaluationRepository;
import com.austin.trading.service.AiTaskService;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.ClaudeCodeRequestWriterService;
import com.austin.trading.service.MarketDataService;
import com.austin.trading.service.ScoreConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 盤前工作流編排器（08:10 觸發）。
 *
 * <pre>
 * Step 1: 確認市場快照已由 PremarketDataPrepJob 建立
 * Step 2: 讀取昨日題材排名，補充 Claude 請求 context
 * Step 3: 候選股初篩（依 candidate.scan.maxCount）
 * Step 4: Java 結構評分（JavaStructureScoringEngine）落表 stock_evaluation
 * Step 5: 寫出 Claude 研究請求（依 candidate.research.maxCount）
 * Step 6: LINE 盤前通知
 * </pre>
 */
@Service
public class PremarketWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(PremarketWorkflowService.class);

    private final MarketDataService              marketDataService;
    private final CandidateScanService           candidateScanService;
    private final ThemeSelectionEngine           themeSelectionEngine;
    private final JavaStructureScoringEngine     javaStructureScoringEngine;
    private final StockEvaluationRepository      stockEvaluationRepository;
    private final ClaudeCodeRequestWriterService requestWriterService;
    private final LineTemplateService            lineTemplateService;
    private final ScoreConfigService             config;
    private final AiTaskService                  aiTaskService;

    public PremarketWorkflowService(
            MarketDataService marketDataService,
            CandidateScanService candidateScanService,
            ThemeSelectionEngine themeSelectionEngine,
            JavaStructureScoringEngine javaStructureScoringEngine,
            StockEvaluationRepository stockEvaluationRepository,
            ClaudeCodeRequestWriterService requestWriterService,
            LineTemplateService lineTemplateService,
            ScoreConfigService config,
            AiTaskService aiTaskService
    ) {
        this.marketDataService         = marketDataService;
        this.candidateScanService      = candidateScanService;
        this.themeSelectionEngine      = themeSelectionEngine;
        this.javaStructureScoringEngine = javaStructureScoringEngine;
        this.stockEvaluationRepository = stockEvaluationRepository;
        this.requestWriterService      = requestWriterService;
        this.lineTemplateService       = lineTemplateService;
        this.config                    = config;
        this.aiTaskService             = aiTaskService;
    }

    public void execute(LocalDate tradingDate) {
        log.info("[PremarketWorkflow] 開始 tradingDate={}", tradingDate);

        // Step 1: 市場快照已由 PremarketDataPrepJob 建立，此處確認存在
        boolean hasMarket = marketDataService.getCurrentMarket().isPresent();
        if (!hasMarket) {
            log.warn("[PremarketWorkflow] 無市場快照，跳過後續步驟");
            return;
        }

        // Step 2: 讀取昨日題材排名（供 Claude 請求 context 參考）
        String themeContext = buildThemeContext(tradingDate.minusDays(1));

        // Step 3: 取得候選股
        int scanMax = config.getInt("candidate.scan.maxCount", 8);
        List<CandidateResponse> candidates = candidateScanService.getCandidatesByDate(tradingDate, scanMax);
        if (candidates.isEmpty()) {
            // 若今日無資料，取最新有效日
            candidates = candidateScanService.getCurrentCandidates(scanMax);
        }
        log.info("[PremarketWorkflow] 取得候選股 {} 檔", candidates.size());

        // Step 4: Java 結構評分 → upsert stock_evaluation
        batchComputeJavaStructureScore(candidates, tradingDate);

        // Step 5: 寫出 Claude 研究請求
        int researchMax = config.getInt("candidate.research.maxCount", 3);
        List<String> topSymbols = candidates.stream()
                .limit(researchMax)
                .map(CandidateResponse::symbol)
                .toList();

        String contextPayload = themeContext.isBlank() ? null : themeContext;
        boolean written = requestWriterService.writeRequest("PREMARKET", tradingDate, topSymbols, contextPayload);
        log.info("[PremarketWorkflow] Claude 研究請求寫出={}, symbols={}", written, topSymbols);

        // Step 5.5: 建立 AI 任務（PR-2）供 Claude 認領
        try {
            List<AiTaskCandidateRef> refs = candidates.stream()
                    .limit(researchMax)
                    .map(c -> new AiTaskCandidateRef(
                            c.symbol(), c.stockName(), c.themeTag(), c.javaStructureScore()))
                    .toList();
            aiTaskService.createTask(
                    tradingDate, "PREMARKET", null, refs,
                    "今日盤前研究請求，共 " + refs.size() + " 檔",
                    written ? "D:/ai/stock/claude-research-request.json" : null
            );
        } catch (Exception e) {
            log.warn("[PremarketWorkflow] createTask 失敗: {}", e.getMessage());
        }

        // Step 6: LINE 盤前通知
        boolean lineEnabled = config.getBoolean("scheduling.line_notify_enabled", false);
        if (lineEnabled) {
            MarketCurrentResponse market = marketDataService.getCurrentMarket().orElse(null);
            String marketSummary = market == null
                    ? "（盤前市場資料尚未就緒）"
                    : "行情等級：" + market.marketGrade() + "，階段：" + market.marketPhase();
            String candidateText = candidates.isEmpty() ? "（無候選資料）"
                    : candidates.stream()
                        .map(c -> "  ▶ " + c.symbol()
                                + (c.stockName() == null ? "" : " " + c.stockName())
                                + (c.entryPriceZone() == null ? "" : "  進場區：" + c.entryPriceZone()))
                        .collect(Collectors.joining("\n"));
            lineTemplateService.notifyPremarket(marketSummary, candidateText, tradingDate);
            log.info("[PremarketWorkflow] LINE 盤前通知已發送");
        }
    }

    // ── 私有方法 ──────────────────────────────────────────────────────────────

    /**
     * 讀取指定日題材排名，回傳前 5 名的簡短文字（供 Claude context 參考）。
     */
    private String buildThemeContext(LocalDate date) {
        List<ThemeSnapshotEntity> themes = themeSelectionEngine.getRankedThemes(date);
        if (themes.isEmpty()) return "";
        return "昨日題材排名（前5）：" + themes.stream()
                .limit(5)
                .map(t -> t.getThemeTag()
                        + (t.getFinalThemeScore() != null
                                ? "=" + t.getFinalThemeScore().toPlainString() : ""))
                .collect(Collectors.joining("、"));
    }

    /**
     * 為候選股批次計算 JavaStructureScore 並 upsert 到 stock_evaluation。
     * 若已有更精確的人工分數，仍會覆蓋（盤前預填，後續 09:30 管線會再更新）。
     */
    private void batchComputeJavaStructureScore(List<CandidateResponse> candidates, LocalDate tradingDate) {
        for (CandidateResponse c : candidates) {
            try {
                BigDecimal javaScore = javaStructureScoringEngine.compute(new JavaStructureInput(
                        c.riskRewardRatio(),
                        c.includeInFinalPlan(),
                        c.stopLossPrice(),
                        c.valuationMode(),
                        inferEntryType(c.reason()),
                        c.score(),
                        c.themeTag() != null
                ));

                StockEvaluationEntity eval = stockEvaluationRepository
                        .findByTradingDateAndSymbol(tradingDate, c.symbol())
                        .orElseGet(() -> {
                            StockEvaluationEntity e = new StockEvaluationEntity();
                            e.setTradingDate(tradingDate);
                            e.setSymbol(c.symbol());
                            return e;
                        });
                eval.setJavaStructureScore(javaScore);
                stockEvaluationRepository.save(eval);

            } catch (Exception e) {
                log.warn("[PremarketWorkflow] Java結構評分失敗 symbol={}: {}", c.symbol(), e.getMessage());
            }
        }
        log.info("[PremarketWorkflow] Java結構評分完成，共 {} 檔", candidates.size());
    }

    private String inferEntryType(String reason) {
        if (reason == null) return "PULLBACK";
        String r = reason.toLowerCase(Locale.ROOT);
        if (r.contains("突破") || r.contains("breakout")) return "BREAKOUT";
        if (r.contains("轉強") || r.contains("reversal")) return "REVERSAL";
        return "PULLBACK";
    }
}
