package com.austin.trading.workflow;

import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.engine.ThemeSelectionEngine;
import com.austin.trading.notify.NotificationFacade;
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

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 驗證 PostmarketWorkflowService 的 LINE 通知會依
 * {@code scheduling.line_notify_enabled} 開關，不再硬編碼 false。
 */
class PostmarketWorkflowLineGateTests {

    private CandidateScanService candidateScanService;
    private ThemeSelectionEngine themeSelectionEngine;
    private ThemeStrengthService themeStrengthService;
    private MarketRegimeService marketRegimeService;
    private TradeReviewService tradeReviewService;
    private StockThemeMappingRepository stockThemeMappingRepository;
    private PositionRepository positionRepository;
    private DailyPnlRepository dailyPnlRepository;
    private PnlService pnlService;
    private ClaudeCodeRequestWriterService requestWriterService;
    private NotificationFacade notificationFacade;
    private ScoreConfigService config;
    private AiTaskService aiTaskService;

    private PostmarketWorkflowService service;

    private final LocalDate today = LocalDate.of(2026, 4, 27);

    @BeforeEach
    void setUp() {
        candidateScanService = mock(CandidateScanService.class);
        themeSelectionEngine = mock(ThemeSelectionEngine.class);
        themeStrengthService = mock(ThemeStrengthService.class);
        marketRegimeService = mock(MarketRegimeService.class);
        tradeReviewService = mock(TradeReviewService.class);
        stockThemeMappingRepository = mock(StockThemeMappingRepository.class);
        positionRepository = mock(PositionRepository.class);
        dailyPnlRepository = mock(DailyPnlRepository.class);
        pnlService = mock(PnlService.class);
        requestWriterService = mock(ClaudeCodeRequestWriterService.class);
        notificationFacade = mock(NotificationFacade.class);
        config = mock(ScoreConfigService.class);
        aiTaskService = mock(AiTaskService.class);

        CandidateResponse c = mock(CandidateResponse.class);
        when(c.symbol()).thenReturn("2330");
        when(candidateScanService.getCurrentCandidates(anyInt())).thenReturn(List.of(c));
        when(positionRepository.findByStatus(anyString())).thenReturn(List.of());

        when(config.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(requestWriterService.writeRequest(anyString(), any(), any(), any())).thenReturn(true);
        when(aiTaskService.findLatestMarkdown(any(), anyString(), anyString())).thenReturn(null);

        service = new PostmarketWorkflowService(
                candidateScanService, themeSelectionEngine, themeStrengthService,
                marketRegimeService, tradeReviewService, stockThemeMappingRepository,
                positionRepository, dailyPnlRepository, pnlService,
                requestWriterService, notificationFacade, config, aiTaskService
        );
    }

    @Test
    void lineDisabled_doesNotCallNotifyPostmarket() {
        when(config.getBoolean(eq("scheduling.line_notify_enabled"), anyBoolean())).thenReturn(false);

        service.execute(today);

        verify(notificationFacade, never()).notifyPostmarket(anyString(), any(LocalDate.class));
    }

    @Test
    void lineEnabled_callsNotifyPostmarketOnce() {
        when(config.getBoolean(eq("scheduling.line_notify_enabled"), anyBoolean())).thenReturn(true);

        service.execute(today);

        verify(notificationFacade, times(1)).notifyPostmarket(anyString(), eq(today));
    }
}
