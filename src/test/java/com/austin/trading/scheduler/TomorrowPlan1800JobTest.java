package com.austin.trading.scheduler;

import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.dto.response.NextDayStrategyDto;
import com.austin.trading.notify.NotificationFacade;
import com.austin.trading.service.AiTaskService;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.MarketDataService;
import com.austin.trading.service.NextDayStrategyBuilder;
import com.austin.trading.service.PositionReviewService;
import com.austin.trading.service.PositionService;
import com.austin.trading.service.SchedulerLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TomorrowPlan1800JobTest {

    private MarketDataService marketDataService;
    private CandidateScanService candidateScanService;
    private NotificationFacade notificationFacade;
    private SchedulerLogService schedulerLogService;
    private DailyOrchestrationService orchestrationService;
    private AiTaskService aiTaskService;
    private PositionService positionService;
    private PositionReviewService positionReviewService;
    private NextDayStrategyBuilder nextDayStrategyBuilder;
    private TomorrowPlan1800Job job;

    @BeforeEach
    void setUp() {
        marketDataService = mock(MarketDataService.class);
        candidateScanService = mock(CandidateScanService.class);
        notificationFacade = mock(NotificationFacade.class);
        schedulerLogService = mock(SchedulerLogService.class);
        orchestrationService = mock(DailyOrchestrationService.class);
        aiTaskService = mock(AiTaskService.class);
        positionService = mock(PositionService.class);
        positionReviewService = mock(PositionReviewService.class);
        nextDayStrategyBuilder = mock(NextDayStrategyBuilder.class);

        job = new TomorrowPlan1800Job(
                marketDataService,
                candidateScanService,
                notificationFacade,
                schedulerLogService,
                orchestrationService,
                aiTaskService,
                positionService,
                positionReviewService,
                nextDayStrategyBuilder
        );

        when(orchestrationService.markRunning(any(LocalDate.class), any())).thenReturn(true);
        when(marketDataService.getCurrentMarket()).thenReturn(Optional.of(mock(MarketCurrentResponse.class)));
        when(positionService.getOpenPositions(20)).thenReturn(List.of());
        when(aiTaskService.findLatestMarkdown(any(LocalDate.class), any(), any())).thenReturn(null);
        when(nextDayStrategyBuilder.buildStrategy()).thenReturn(mock(NextDayStrategyDto.class));
    }

    @Test
    void usesNextCandidatesWhenAvailable() {
        when(candidateScanService.getNextCandidates(10)).thenReturn(List.of(candidate("2222")));

        job.run();

        verify(candidateScanService).getNextCandidates(10);
        verify(candidateScanService, never()).getLatestCandidates(10);
        verify(candidateScanService, never()).getCurrentCandidates(10);
        verify(notificationFacade).notifyTomorrowPlan(any(NextDayStrategyDto.class), any(LocalDate.class));
    }

    @Test
    void fallsBackToLatestThenCurrentWhenNextIsEmpty() {
        when(candidateScanService.getNextCandidates(10)).thenReturn(List.of());
        when(candidateScanService.getLatestCandidates(10)).thenReturn(List.of());
        when(candidateScanService.getCurrentCandidates(10)).thenReturn(List.of(candidate("1111")));

        job.run();

        verify(candidateScanService).getNextCandidates(10);
        verify(candidateScanService).getLatestCandidates(10);
        verify(candidateScanService).getCurrentCandidates(10);
        verify(notificationFacade).notifyTomorrowPlan(any(NextDayStrategyDto.class), any(LocalDate.class));
    }

    private CandidateResponse candidate(String symbol) {
        return new CandidateResponse(
                LocalDate.now(),
                symbol,
                "測試股",
                null,
                null,
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null
        );
    }
}
