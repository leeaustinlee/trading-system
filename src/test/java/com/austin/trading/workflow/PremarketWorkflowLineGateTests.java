package com.austin.trading.workflow;

import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.engine.JavaStructureScoringEngine;
import com.austin.trading.engine.ThemeSelectionEngine;
import com.austin.trading.notify.NotificationFacade;
import com.austin.trading.repository.StockEvaluationRepository;
import com.austin.trading.service.AiTaskService;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.ClaudeCodeRequestWriterService;
import com.austin.trading.service.MarketDataService;
import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 驗證 PremarketWorkflowService 的 LINE 通知會依
 * {@code scheduling.line_notify_enabled} 開關，不再硬編碼 false。
 */
class PremarketWorkflowLineGateTests {

    private MarketDataService marketDataService;
    private CandidateScanService candidateScanService;
    private ThemeSelectionEngine themeSelectionEngine;
    private JavaStructureScoringEngine scoringEngine;
    private StockEvaluationRepository stockEvaluationRepository;
    private ClaudeCodeRequestWriterService requestWriterService;
    private NotificationFacade notificationFacade;
    private ScoreConfigService config;
    private AiTaskService aiTaskService;

    private PremarketWorkflowService service;

    private final LocalDate today = LocalDate.of(2026, 4, 27);

    @BeforeEach
    void setUp() {
        marketDataService = mock(MarketDataService.class);
        candidateScanService = mock(CandidateScanService.class);
        themeSelectionEngine = mock(ThemeSelectionEngine.class);
        scoringEngine = mock(JavaStructureScoringEngine.class);
        stockEvaluationRepository = mock(StockEvaluationRepository.class);
        requestWriterService = mock(ClaudeCodeRequestWriterService.class);
        notificationFacade = mock(NotificationFacade.class);
        config = mock(ScoreConfigService.class);
        aiTaskService = mock(AiTaskService.class);

        // ── stub minimum required to reach Step 6 ────────────────────────────
        MarketCurrentResponse market = new MarketCurrentResponse(
                1L, today, "B", "OPEN_TEST", "WATCH", "{}", LocalDateTime.now());
        when(marketDataService.getCurrentMarket()).thenReturn(Optional.of(market));

        CandidateResponse c = mock(CandidateResponse.class);
        when(c.symbol()).thenReturn("2330");
        when(candidateScanService.getCandidatesByDate(eq(today), anyInt()))
                .thenReturn(List.of(c));
        when(candidateScanService.getCurrentCandidates(anyInt()))
                .thenReturn(List.of(c));

        when(config.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(requestWriterService.writeRequest(anyString(), any(), any(), any())).thenReturn(true);
        when(aiTaskService.getByDate(any())).thenReturn(List.of());

        service = new PremarketWorkflowService(
                marketDataService, candidateScanService, themeSelectionEngine,
                scoringEngine, stockEvaluationRepository, requestWriterService,
                notificationFacade, config, aiTaskService
        );
    }

    @Test
    void lineDisabled_doesNotCallLineTemplateService() {
        when(config.getBoolean(eq("scheduling.line_notify_enabled"), anyBoolean())).thenReturn(false);

        service.execute(today);

        verify(notificationFacade, never())
                .notifyPremarket(anyString(), anyString(), any(LocalDate.class));
    }

    @Test
    void lineEnabled_callsLineTemplateServiceOnce() {
        when(config.getBoolean(eq("scheduling.line_notify_enabled"), anyBoolean())).thenReturn(true);

        service.execute(today);

        verify(notificationFacade, times(1))
                .notifyPremarket(anyString(), anyString(), eq(today));
    }

    @Test
    void noMarketSnapshot_skipsEntirely_evenIfLineEnabled() {
        when(marketDataService.getCurrentMarket()).thenReturn(Optional.empty());
        when(config.getBoolean(eq("scheduling.line_notify_enabled"), anyBoolean())).thenReturn(true);

        service.execute(today);

        verify(notificationFacade, never())
                .notifyPremarket(anyString(), anyString(), any(LocalDate.class));
    }

    @Test
    void lineEnabled_invokesWriteRequest() {
        when(config.getBoolean(eq("scheduling.line_notify_enabled"), anyBoolean())).thenReturn(true);

        service.execute(today);

        verify(requestWriterService, atLeastOnce())
                .writeRequest(eq("PREMARKET"), eq(today), any(), any());
    }
}
