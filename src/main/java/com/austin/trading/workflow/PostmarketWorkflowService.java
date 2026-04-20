package com.austin.trading.workflow;

import com.austin.trading.dto.request.AiTaskCandidateRef;
import com.austin.trading.dto.request.DailyPnlCreateRequest;
import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.dto.response.LiveQuoteResponse;
import com.austin.trading.engine.ThemeSelectionEngine;
import com.austin.trading.entity.StockThemeMappingEntity;
import com.austin.trading.entity.ThemeSnapshotEntity;
import com.austin.trading.notify.LineTemplateService;
import com.austin.trading.repository.DailyPnlRepository;
import com.austin.trading.repository.PositionRepository;
import com.austin.trading.repository.StockThemeMappingRepository;
import com.austin.trading.service.AiTaskService;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.ClaudeCodeRequestWriterService;
import com.austin.trading.service.PnlService;
import com.austin.trading.service.ScoreConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 盤後工作流編排器（15:30 觸發）。
 *
 * <pre>
 * Step 1: 整理當日倉位損益 → upsert daily_pnl
 * Step 2: 全市場題材強弱掃描 → ThemeSelectionEngine.computeAndSaveAllThemes()
 * Step 3: 以今日候選股為基礎，寫出明日 Claude 研究請求
 * Step 4: LINE 盤後通知
 * </pre>
 */
@Service
public class PostmarketWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(PostmarketWorkflowService.class);

    private final CandidateScanService           candidateScanService;
    private final ThemeSelectionEngine           themeSelectionEngine;
    private final StockThemeMappingRepository    stockThemeMappingRepository;
    private final PositionRepository             positionRepository;
    private final DailyPnlRepository             dailyPnlRepository;
    private final PnlService                     pnlService;
    private final ClaudeCodeRequestWriterService requestWriterService;
    private final LineTemplateService            lineTemplateService;
    private final ScoreConfigService             config;
    private final AiTaskService                  aiTaskService;

    public PostmarketWorkflowService(
            CandidateScanService candidateScanService,
            ThemeSelectionEngine themeSelectionEngine,
            StockThemeMappingRepository stockThemeMappingRepository,
            PositionRepository positionRepository,
            DailyPnlRepository dailyPnlRepository,
            PnlService pnlService,
            ClaudeCodeRequestWriterService requestWriterService,
            LineTemplateService lineTemplateService,
            ScoreConfigService config,
            AiTaskService aiTaskService
    ) {
        this.candidateScanService       = candidateScanService;
        this.themeSelectionEngine       = themeSelectionEngine;
        this.stockThemeMappingRepository = stockThemeMappingRepository;
        this.positionRepository         = positionRepository;
        this.dailyPnlRepository         = dailyPnlRepository;
        this.pnlService                 = pnlService;
        this.requestWriterService       = requestWriterService;
        this.lineTemplateService        = lineTemplateService;
        this.config                     = config;
        this.aiTaskService              = aiTaskService;
    }

    public void execute(LocalDate tradingDate) {
        log.info("[PostmarketWorkflow] 開始 tradingDate={}", tradingDate);

        // Step 1: 整理當日倉位損益
        consolidateDailyPnl(tradingDate);

        // Step 2: 全市場題材強弱掃描
        computeThemeScores(tradingDate);

        // Step 3: 以今日候選股為基礎，寫出明日 Claude 研究請求
        int researchMax = config.getInt("candidate.research.maxCount", 3);
        List<CandidateResponse> candidates = candidateScanService.getCurrentCandidates(researchMax);
        List<String> symbols = candidates.stream().map(CandidateResponse::symbol).toList();

        if (!symbols.isEmpty()) {
            // v2.1：Claude request 仍由 workflow 維護（作為溝通橋樑），
            // 但 AI task 的建立已移到 PostmarketDataPrepJob (15:05)。
            // 本 workflow (15:30) 只讀已完成 POSTMARKET task，不再自行 createTask。
            boolean written = requestWriterService.writeRequest("POSTMARKET", tradingDate, symbols, null);
            log.info("[PostmarketWorkflow] Claude 研究請求寫出={}, symbols={}（task 由 DataPrep 建立）",
                    written, symbols);
        }

        // Step 4: LINE 盤後通知
        boolean lineEnabled = config.getBoolean("scheduling.line_notify_enabled", false);
        if (lineEnabled) {
            int notifyMax = config.getInt("candidate.notify.maxCount", 5);
            List<CandidateResponse> notifyList = candidateScanService.getCurrentCandidates(notifyMax);
            String candidateText = formatCandidates(notifyList);
            lineTemplateService.notifyPostmarket(candidateText, tradingDate);
            log.info("[PostmarketWorkflow] LINE 盤後通知已發送，候選股={} 檔", notifyList.size());

            // 補發：POSTMARKET / AFTERMARKET / MIDDAY 任一 AI 研究 md
            String aiMd = aiTaskService.findLatestMarkdown(tradingDate, "POSTMARKET", "MIDDAY");
            if (aiMd != null && aiMd.length() > 100) {
                String summary = aiMd.length() > 3500
                        ? aiMd.substring(0, 3500) + "\n...(內容過長已截斷)"
                        : aiMd;
                lineTemplateService.notifySystemAlert("📎 15:30 AI 研究摘要", summary);
            }
        } else {
            log.info("[PostmarketWorkflow] LINE 通知未啟用（scheduling.line_notify_enabled=false）");
        }
    }

    // ── 私有方法 ──────────────────────────────────────────────────────────────

    /**
     * Step 1：整理當日已實現損益。
     * <p>
     * 持倉出清時 {@link PnlService#recordClosedPosition} 已即時更新 daily_pnl，
     * 此處做盤後確認：若今日無記錄但有已關閉持倉，則建立彙總記錄。
     * </p>
     */
    private void consolidateDailyPnl(LocalDate tradingDate) {
        try {
            boolean alreadyExists = dailyPnlRepository.findByTradingDate(tradingDate).isPresent();
            if (alreadyExists) {
                log.info("[PostmarketWorkflow] daily_pnl 已存在（持倉出清時已自動更新），跳過重建");
                return;
            }

            // 查詢今日已關閉持倉的已實現損益加總
            LocalDateTime start = tradingDate.atStartOfDay();
            LocalDateTime end   = tradingDate.plusDays(1).atStartOfDay();
            BigDecimal totalPnl = positionRepository.sumRealizedPnlBetween(start, end);

            if (totalPnl == null) {
                log.info("[PostmarketWorkflow] 今日無已關閉持倉，不建立 daily_pnl");
                return;
            }

            // 統計交易筆數
            int tradeCount = positionRepository.findClosedBetween(start, end).size();

            pnlService.create(new DailyPnlCreateRequest(
                    tradingDate, totalPnl, null, null,
                    tradeCount, null, null,
                    "Auto: postmarket workflow 盤後確認",
                    null, null, null, null
            ));
            log.info("[PostmarketWorkflow] daily_pnl 建立完成 tradingDate={} grossPnl={}", tradingDate, totalPnl);

        } catch (Exception e) {
            log.warn("[PostmarketWorkflow] consolidateDailyPnl 失敗: {}", e.getMessage());
        }
    }

    /**
     * Step 2：取得題材成員股票的即時收盤報價，計算並儲存各題材分數。
     */
    private void computeThemeScores(LocalDate tradingDate) {
        try {
            // 取所有啟用題材的成員股代號（去重）
            List<String> themeSymbols = stockThemeMappingRepository
                    .findAllByOrderBySymbolAscThemeTagAsc()
                    .stream()
                    .filter(m -> Boolean.TRUE.equals(m.getIsActive()))
                    .map(StockThemeMappingEntity::getSymbol)
                    .distinct()
                    .toList();

            if (themeSymbols.isEmpty()) {
                log.info("[PostmarketWorkflow] 無啟用題材成員，跳過題材評分");
                return;
            }

            // 取得即時報價（盤後會是收盤價）
            List<LiveQuoteResponse> quotes = candidateScanService.getLiveQuotesBySymbols(themeSymbols);

            // 轉換為 StockQuoteInput
            List<ThemeSelectionEngine.StockQuoteInput> quoteInputs = quotes.stream()
                    .filter(q -> q.changePercent() != null || q.currentPrice() != null)
                    .map(q -> new ThemeSelectionEngine.StockQuoteInput(
                            q.symbol(), q.changePercent(), q.volume(), q.currentPrice()))
                    .toList();

            if (quoteInputs.isEmpty()) {
                log.info("[PostmarketWorkflow] 題材成員均無報價，跳過題材評分");
                return;
            }

            List<ThemeSnapshotEntity> results =
                    themeSelectionEngine.computeAndSaveAllThemes(tradingDate, quoteInputs);
            log.info("[PostmarketWorkflow] 題材評分完成，共更新 {} 個題材", results.size());

        } catch (Exception e) {
            log.warn("[PostmarketWorkflow] computeThemeScores 失敗: {}", e.getMessage());
        }
    }

    private String formatCandidates(List<CandidateResponse> list) {
        if (list.isEmpty()) return "（今日無候選資料，請確認掃描流程）";
        return list.stream()
                .map(c -> {
                    String name = c.stockName() == null ? "" : " " + c.stockName();
                    String zone = c.entryPriceZone() == null ? "" : "  區間：" + c.entryPriceZone();
                    String rr   = c.riskRewardRatio() == null ? "" :
                            String.format("  RR：%.2f", c.riskRewardRatio().doubleValue());
                    return "  ▶ " + c.symbol() + name + zone + rr;
                })
                .collect(Collectors.joining("\n"));
    }
}
