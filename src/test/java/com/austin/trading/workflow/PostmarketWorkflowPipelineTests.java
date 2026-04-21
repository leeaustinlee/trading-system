package com.austin.trading.workflow;

import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.ThemeStrengthDecision;
import com.austin.trading.engine.ThemeSelectionEngine;
import com.austin.trading.notify.LineTemplateService;
import com.austin.trading.repository.DailyPnlRepository;
import com.austin.trading.repository.PositionRepository;
import com.austin.trading.repository.StockThemeMappingRepository;
import com.austin.trading.service.AiTaskService;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.ClaudeCodeRequestWriterService;
import com.austin.trading.service.MarketRegimeService;
import com.austin.trading.service.PnlService;
import com.austin.trading.service.ScoreConfigService;
import com.austin.trading.service.ThemeStrengthService;
import com.austin.trading.service.TradeReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1.3 Workflow Rewire — verifies PostmarketWorkflowService calls the new pipeline steps.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PostmarketWorkflowPipelineTests {

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
    @Mock LineTemplateService            lineTemplateService;
    @Mock ScoreConfigService             config;
    @Mock AiTaskService                  aiTaskService;

    private PostmarketWorkflowService workflow;
    private static final LocalDate DATE = LocalDate.of(2026, 4, 21);

    @BeforeEach
    void setUp() {
        workflow = new PostmarketWorkflowService(
                candidateScanService, themeSelectionEngine,
                themeStrengthService, marketRegimeService, tradeReviewService,
                stockThemeMappingRepository, positionRepository,
                dailyPnlRepository, pnlService,
                requestWriterService, lineTemplateService, config, aiTaskService
        );

        // common stubs
        when(dailyPnlRepository.findByTradingDate(any())).thenReturn(Optional.empty());
        when(positionRepository.sumRealizedPnlBetween(any(), any())).thenReturn(null);
        when(stockThemeMappingRepository.findAllByOrderBySymbolAscThemeTagAsc()).thenReturn(List.of());
        when(candidateScanService.getCurrentCandidates(anyInt())).thenReturn(List.of());
        when(config.getInt(anyString(), anyInt())).thenReturn(3);
        when(config.getBoolean(anyString(), any(Boolean.class))).thenReturn(false);
        when(requestWriterService.writeRequest(any(), any(), any(), any())).thenReturn(true);
    }

    // ── Step 1a: daily trade review ───────────────────────────────────────────

    @Test
    void execute_callsTradeReviewForAllUnreviewed() {
        when(tradeReviewService.generateForAllUnreviewed()).thenReturn(0);
        when(marketRegimeService.getLatestForToday()).thenReturn(Optional.empty());
        when(themeStrengthService.evaluateAll(any(), any())).thenReturn(Map.of());

        workflow.execute(DATE);

        verify(tradeReviewService).generateForAllUnreviewed();
    }

    @Test
    void execute_tradeReviewException_doesNotAbortWorkflow() {
        when(tradeReviewService.generateForAllUnreviewed()).thenThrow(new RuntimeException("DB down"));
        when(marketRegimeService.getLatestForToday()).thenReturn(Optional.empty());
        when(themeStrengthService.evaluateAll(any(), any())).thenReturn(Map.of());

        // should not throw
        workflow.execute(DATE);

        verify(themeStrengthService).evaluateAll(any(), any());
    }

    // ── Step 2b: theme strength ───────────────────────────────────────────────

    @Test
    void execute_callsThemeStrengthEvaluateAll() {
        when(tradeReviewService.generateForAllUnreviewed()).thenReturn(0);
        when(marketRegimeService.getLatestForToday()).thenReturn(Optional.empty());
        when(themeStrengthService.evaluateAll(any(), any())).thenReturn(Map.of());

        workflow.execute(DATE);

        verify(themeStrengthService).evaluateAll(DATE, null);
    }

    @Test
    void execute_passesRegimeToThemeStrength() {
        MarketRegimeDecision regime = new MarketRegimeDecision(
                DATE, null, "BULL_TREND", "A", true, null, List.of(), null, null, null
        );
        ThemeStrengthDecision td = new ThemeStrengthDecision(DATE, "AI", null, "MID_TREND", null, true, null, null);
        when(tradeReviewService.generateForAllUnreviewed()).thenReturn(0);
        when(marketRegimeService.getLatestForToday()).thenReturn(Optional.of(regime));
        when(themeStrengthService.evaluateAll(DATE, regime)).thenReturn(Map.of("AI", td));

        workflow.execute(DATE);

        verify(themeStrengthService).evaluateAll(DATE, regime);
    }

    @Test
    void execute_themeStrengthException_doesNotAbortWorkflow() {
        when(tradeReviewService.generateForAllUnreviewed()).thenReturn(0);
        when(marketRegimeService.getLatestForToday()).thenReturn(Optional.empty());
        when(themeStrengthService.evaluateAll(any(), any())).thenThrow(new RuntimeException("timeout"));

        // should complete without throwing even if step 2b fails
        workflow.execute(DATE);
    }

    // ── pipeline order ────────────────────────────────────────────────────────

    @Test
    void execute_lineDisabled_skipsLineNotify() {
        when(tradeReviewService.generateForAllUnreviewed()).thenReturn(0);
        when(marketRegimeService.getLatestForToday()).thenReturn(Optional.empty());
        when(themeStrengthService.evaluateAll(any(), any())).thenReturn(Map.of());
        when(config.getBoolean("scheduling.line_notify_enabled", false)).thenReturn(false);

        workflow.execute(DATE);

        verify(lineTemplateService, never()).notifyPostmarket(any(), any());
    }

}
