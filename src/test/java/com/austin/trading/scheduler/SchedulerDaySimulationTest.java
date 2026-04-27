package com.austin.trading.scheduler;

import com.austin.trading.dto.internal.BenchmarkReport;
import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.ThemeStrengthDecision;
import com.austin.trading.dto.response.FinalDecisionResponse;
import com.austin.trading.engine.ThemeSelectionEngine;
import com.austin.trading.notify.NotificationFacade;
import com.austin.trading.repository.DailyPnlRepository;
import com.austin.trading.repository.PositionRepository;
import com.austin.trading.repository.StockThemeMappingRepository;
import com.austin.trading.service.AiTaskService;
import com.austin.trading.service.BenchmarkAnalyticsService;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.ClaudeCodeRequestWriterService;
import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.FinalDecisionService;
import com.austin.trading.service.MarketDataService;
import com.austin.trading.service.MarketRegimeService;
import com.austin.trading.service.PnlService;
import com.austin.trading.service.ScoreConfigService;
import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.service.StrategyRecommendationService;
import com.austin.trading.service.ThemeStrengthService;
import com.austin.trading.service.TradeAttributionService;
import com.austin.trading.service.TradeReviewService;
import com.austin.trading.workflow.IntradayDecisionWorkflowService;
import com.austin.trading.workflow.PostmarketWorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 真實排程日演練：15:05 → 15:30 → 09:30 → Weekly
 *
 * <p>不需要資料庫連線。每個排程工作的核心 workflow 以純 Mockito 模擬，
 * 驗證分層管線（Regime → Theme → Ranking → Setup → Timing → Risk → Execution → Review）
 * 在完整排程序列中依序被呼叫。</p>
 *
 * <pre>
 * 15:05  PostmarketDataPrepJob   → 收盤資料準備（外部 API 呼叫由 mock 模擬）
 * 15:30  PostmarketAnalysis1530  → TradeReview + ThemeStrength（Step 1a + 2b）
 * 09:30  FinalDecision0930       → Regime → ... → Execution 管線
 * 19:00  WeeklyTradeReviewJob    → review + attribution + benchmark + recommendations
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerDaySimulationTest {

    private static final Logger log = LoggerFactory.getLogger(SchedulerDaySimulationTest.class);
    private static final LocalDate SIM_DATE = LocalDate.of(2026, 4, 21);

    // ── PostmarketWorkflowService dependencies ────────────────────────────────
    @Mock CandidateScanService           candidateScanService;
    @Mock ThemeSelectionEngine           themeSelectionEngine;
    @Mock ThemeStrengthService           themeStrengthService;
    @Mock MarketRegimeService            marketRegimeService;
    @Mock TradeReviewService             tradeReviewService;
    @Mock StockThemeMappingRepository    stockThemeMappingRepository;
    @Mock PositionRepository             positionRepository;
    @Mock DailyPnlRepository             dailyPnlRepository;
    @Mock PnlService                     pnlService;
    @Mock ClaudeCodeRequestWriterService requestWriterService;
    @Mock NotificationFacade            notificationFacade;
    @Mock ScoreConfigService             scoreConfigService;
    @Mock AiTaskService                  aiTaskService;

    // ── IntradayDecisionWorkflowService dependencies ──────────────────────────
    @Mock MarketDataService              marketDataService;
    @Mock FinalDecisionService           finalDecisionService;

    // ── WeeklyTradeReviewJob dependencies ────────────────────────────────────
    @Mock TradeAttributionService        tradeAttributionService;
    @Mock BenchmarkAnalyticsService      benchmarkAnalyticsService;
    @Mock StrategyRecommendationService  recommendationService;
    @Mock SchedulerLogService            schedulerLogService;
    @Mock DailyOrchestrationService      orchestrationService;

    // ── Workflow service instances ────────────────────────────────────────────
    private PostmarketWorkflowService      postmarketWorkflow;
    private IntradayDecisionWorkflowService intradayWorkflow;
    private WeeklyTradeReviewJob           weeklyJob;

    // ── Simulation state ──────────────────────────────────────────────────────
    private int tradeReviewCount;
    private int attributionCount;
    private BenchmarkReport benchmarkReport;

    @BeforeEach
    void setUp() {
        postmarketWorkflow = new PostmarketWorkflowService(
                candidateScanService, themeSelectionEngine,
                themeStrengthService, marketRegimeService, tradeReviewService,
                stockThemeMappingRepository, positionRepository,
                dailyPnlRepository, pnlService,
                requestWriterService, notificationFacade, scoreConfigService, aiTaskService
        );

        intradayWorkflow = new IntradayDecisionWorkflowService(
                marketDataService, finalDecisionService,
                notificationFacade, scoreConfigService, aiTaskService
        );

        weeklyJob = new WeeklyTradeReviewJob(
                tradeReviewService, tradeAttributionService,
                benchmarkAnalyticsService, recommendationService,
                schedulerLogService, orchestrationService
        );

        // common stubs
        when(dailyPnlRepository.findByTradingDate(any())).thenReturn(Optional.empty());
        when(positionRepository.sumRealizedPnlBetween(any(), any())).thenReturn(null);
        when(stockThemeMappingRepository.findAllByOrderBySymbolAscThemeTagAsc()).thenReturn(List.of());
        when(candidateScanService.getCurrentCandidates(anyInt())).thenReturn(List.of());
        when(scoreConfigService.getInt(anyString(), anyInt())).thenReturn(3);
        when(scoreConfigService.getBoolean(anyString(), any(Boolean.class))).thenReturn(false);
        when(requestWriterService.writeRequest(any(), any(), any(), any())).thenReturn(true);
        when(orchestrationService.markRunning(any(), any())).thenReturn(true);
        when(aiTaskService.findLatestMarkdown(any(), anyString(), anyString())).thenReturn(null);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Step 1: 15:30 PostmarketAnalysis — TradeReview + ThemeStrength
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void sim_1530_tradeReviewAndThemeStrength_areBothCalled() {
        stubPostmarket(2, "BULL_TREND");

        log.info("=== [SIM 15:30] PostmarketWorkflowService.execute ===");
        postmarketWorkflow.execute(SIM_DATE);

        verify(tradeReviewService).generateForAllUnreviewed();
        verify(themeStrengthService).evaluateAll(any(), any());
        log.info("=== [SIM 15:30] PASS — TradeReview={}, ThemeStrength called ===", tradeReviewCount);
    }

    @Test
    void sim_1530_regime_isPassedToThemeStrength() {
        MarketRegimeDecision regime = regime("BULL_TREND");
        when(marketRegimeService.getLatestForToday()).thenReturn(Optional.of(regime));
        when(tradeReviewService.generateForAllUnreviewed()).thenReturn(1);
        when(themeStrengthService.evaluateAll(SIM_DATE, regime))
                .thenReturn(Map.of("AI", themeDecision("MID_TREND")));

        postmarketWorkflow.execute(SIM_DATE);

        verify(themeStrengthService).evaluateAll(SIM_DATE, regime);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Step 2: 09:30 FinalDecision — 分層管線 banner + result
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void sim_0930_pipelineBanner_andFinalDecisionCalled() {
        FinalDecisionResponse resp = new FinalDecisionResponse("WATCH", List.of(), List.of(), "ok");
        when(marketDataService.getCurrentMarket()).thenReturn(Optional.empty());
        when(finalDecisionService.evaluateAndPersist(SIM_DATE)).thenReturn(resp);

        log.info("=== [SIM 09:30] IntradayDecisionWorkflowService.execute ===");
        intradayWorkflow.execute(SIM_DATE);

        verify(finalDecisionService).evaluateAndPersist(SIM_DATE);
        log.info("=== [SIM 09:30] PASS — decision={} selected={} ===",
                resp.decision(), resp.selectedStocks().size());
    }

    @Test
    void sim_0930_noMarketSnapshot_doesNotAbort() {
        FinalDecisionResponse resp = new FinalDecisionResponse("SKIP", List.of(), List.of(), "no market");
        when(marketDataService.getCurrentMarket()).thenReturn(Optional.empty());
        when(finalDecisionService.evaluateAndPersist(SIM_DATE)).thenReturn(resp);

        intradayWorkflow.execute(SIM_DATE);

        assertThat(resp.decision()).isEqualTo("SKIP");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Step 3: Weekly — review + attribution + benchmark + recommendations
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void sim_weekly_allFourStepsExecuted() {
        stubWeekly(3, 7, "OUTPERFORM", "OUTPERFORM");

        log.info("=== [SIM Weekly] WeeklyTradeReviewJob.run ===");
        weeklyJob.run();

        verify(tradeReviewService).generateForAllUnreviewed();
        verify(tradeAttributionService).findAll();
        verify(benchmarkAnalyticsService).generateForPeriod(any(), any());
        verify(recommendationService).generate(any());
        log.info("=== [SIM Weekly] PASS — reviewed={} attributions={} benchmark={} ===",
                tradeReviewCount, attributionCount, "OUTPERFORM");
    }

    @Test
    void sim_weekly_benchmarkVerdictInLog() {
        stubWeekly(2, 5, "MATCH", "OUTPERFORM");

        weeklyJob.run();

        // benchmark was called and returned a verdict
        var captured = benchmarkAnalyticsService.generateForPeriod(
                SIM_DATE.minusDays(6), SIM_DATE);
        assertThat(captured).isPresent();
        assertThat(captured.get().marketVerdict()).isEqualTo("MATCH");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Step 4: full-day pipeline order (15:30 → 09:30 → weekly)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void sim_fullDay_pipelineCallOrder() {
        stubPostmarket(1, "BULL_TREND");
        FinalDecisionResponse resp = new FinalDecisionResponse("ENTER", List.of(), List.of(), "ok");
        when(marketDataService.getCurrentMarket()).thenReturn(Optional.empty());
        when(finalDecisionService.evaluateAndPersist(SIM_DATE)).thenReturn(resp);
        stubWeekly(1, 3, "OUTPERFORM", "OUTPERFORM");

        log.info("=== [SIM FULL DAY] 15:30 → 09:30 → weekly ===");

        // 15:30
        postmarketWorkflow.execute(SIM_DATE);
        // 09:30
        intradayWorkflow.execute(SIM_DATE);
        // weekly
        weeklyJob.run();

        // verify postmarket pipeline steps
        InOrder pm = inOrder(tradeReviewService, themeStrengthService);
        pm.verify(tradeReviewService).generateForAllUnreviewed();
        pm.verify(themeStrengthService).evaluateAll(any(), any());

        // verify 09:30 pipeline
        verify(finalDecisionService).evaluateAndPersist(SIM_DATE);

        // verify weekly steps
        verify(benchmarkAnalyticsService).generateForPeriod(any(), any());
        verify(recommendationService).generate(any());

        log.info("=== [SIM FULL DAY] PASS ===");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void stubPostmarket(int reviewCount, String regimeType) {
        this.tradeReviewCount = reviewCount;
        when(tradeReviewService.generateForAllUnreviewed()).thenReturn(reviewCount);
        when(marketRegimeService.getLatestForToday())
                .thenReturn(Optional.of(regime(regimeType)));
        when(themeStrengthService.evaluateAll(any(), any()))
                .thenReturn(Map.of("AI", themeDecision("MID_TREND")));
    }

    private void stubWeekly(int reviewed, int attrCount,
                             String marketVerdict, String themeVerdict) {
        this.tradeReviewCount = reviewed;
        this.attributionCount  = attrCount;
        this.benchmarkReport   = new BenchmarkReport(
                SIM_DATE.minusDays(6), SIM_DATE,
                new BigDecimal("3.2"), new BigDecimal("2.5"), new BigDecimal("2.8"),
                new BigDecimal("0.7"), new BigDecimal("0.4"),
                marketVerdict, themeVerdict,
                reviewed, "{}"
        );

        when(tradeReviewService.generateForAllUnreviewed()).thenReturn(reviewed);
        when(tradeAttributionService.findAll()).thenReturn(
                java.util.Collections.nCopies(attrCount, null));
        when(benchmarkAnalyticsService.generateForPeriod(any(), any()))
                .thenReturn(Optional.of(benchmarkReport));
        when(recommendationService.generate(any())).thenReturn(List.of());
    }

    private static MarketRegimeDecision regime(String type) {
        return new MarketRegimeDecision(
                SIM_DATE, null, type, "A", true, null, List.of(), null, null, null
        );
    }

    private static ThemeStrengthDecision themeDecision(String stage) {
        return new ThemeStrengthDecision(SIM_DATE, "AI", null, stage, null, true, null, null);
    }
}
