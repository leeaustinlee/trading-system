package com.austin.trading.scheduler;

import com.austin.trading.dto.request.AiTaskCandidateRef;
import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.dto.response.TradingStateResponse;
import com.austin.trading.entity.AiTaskEntity;
import com.austin.trading.notify.NotificationFacade;
import com.austin.trading.service.AiTaskService;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.ClaudeCodeRequestWriterService;
import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.MarketDataService;
import com.austin.trading.service.OrchestrationStep;
import com.austin.trading.service.PositionService;
import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.service.TradingStateService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MiddayReviewJobRequestContractTest {

    private final MarketDataService marketDataService = mock(MarketDataService.class);
    private final TradingStateService tradingStateService = mock(TradingStateService.class);
    private final PositionService positionService = mock(PositionService.class);
    private final NotificationFacade notificationFacade = mock(NotificationFacade.class);
    private final SchedulerLogService schedulerLogService = mock(SchedulerLogService.class);
    private final DailyOrchestrationService orchestrationService = mock(DailyOrchestrationService.class);
    private final AiTaskService aiTaskService = mock(AiTaskService.class);
    private final CandidateScanService candidateScanService = mock(CandidateScanService.class);
    private final ClaudeCodeRequestWriterService requestWriterService = mock(ClaudeCodeRequestWriterService.class);

    @Test
    void run_writesMiddayRequestWithLiveDecisionContext() {
        when(orchestrationService.markRunning(any(LocalDate.class), eq(OrchestrationStep.MIDDAY_REVIEW)))
                .thenReturn(true);
        when(marketDataService.getCurrentMarket()).thenReturn(Optional.of(
                new MarketCurrentResponse(1L, LocalDate.of(2026, 5, 11), "B", "MIDDAY", "WATCH", "{}", LocalDateTime.now())));
        when(tradingStateService.getCurrentState()).thenReturn(Optional.of(
                new TradingStateResponse(1L, LocalDate.of(2026, 5, 11), "B", "LOCKED", "MIDDAY", "OFF", "WATCH", "{}", LocalDateTime.now())));
        when(positionService.getOpenPositions(20)).thenReturn(List.of());
        when(candidateScanService.getCurrentCandidates(10)).thenReturn(List.of(candidate("1216", "統一", "食品", "7.5")));
        AiTaskEntity task = new AiTaskEntity();
        task.setId(201L);
        when(aiTaskService.createTask(any(LocalDate.class), eq("MIDDAY"), eq(null), anyList(), anyString(), anyString()))
                .thenReturn(task);
        when(requestWriterService.writeRequest(any(), anyString(), any(LocalDate.class), anyList(), anyString()))
                .thenReturn(true);

        newJob().run();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiTaskCandidateRef>> refsCap = ArgumentCaptor.forClass(List.class);
        verify(aiTaskService).createTask(any(LocalDate.class), eq("MIDDAY"), eq(null), refsCap.capture(), anyString(), anyString());
        assertThat(refsCap.getValue()).extracting(AiTaskCandidateRef::symbol).containsExactly("1216");

        ArgumentCaptor<String> contextCap = ArgumentCaptor.forClass(String.class);
        verify(requestWriterService).writeRequest(eq(201L), eq("MIDDAY"), any(LocalDate.class), eq(List.of("1216")), contextCap.capture());
        assertThat(contextCap.getValue())
                .contains("\"session\":\"MIDDAY_REVIEW\"")
                .contains("\"marketGrade\":\"B\"")
                .contains("\"decisionLock\":\"LOCKED\"")
                .contains("\"monitorMode\":\"WATCH\"")
                .contains("\"hourlyGate\":\"OFF\"");
    }

    @Test
    void run_writeRequestReturnsFalse_throwsAndDoesNotMarkDone() {
        when(orchestrationService.markRunning(any(LocalDate.class), eq(OrchestrationStep.MIDDAY_REVIEW)))
                .thenReturn(true);
        when(marketDataService.getCurrentMarket()).thenReturn(Optional.empty());
        when(tradingStateService.getCurrentState()).thenReturn(Optional.empty());
        when(positionService.getOpenPositions(20)).thenReturn(List.of());
        when(candidateScanService.getCurrentCandidates(10)).thenReturn(List.of(candidate("1216", "統一", "食品", "7.5")));
        AiTaskEntity task = new AiTaskEntity();
        task.setId(201L);
        when(aiTaskService.createTask(any(LocalDate.class), eq("MIDDAY"), eq(null), anyList(), anyString(), anyString()))
                .thenReturn(task);
        when(requestWriterService.writeRequest(any(), anyString(), any(LocalDate.class), anyList(), anyString()))
                .thenReturn(false);

        assertThatThrownBy(() -> newJob().run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MIDDAY request");
        verify(orchestrationService, never()).markDone(any(LocalDate.class), eq(OrchestrationStep.MIDDAY_REVIEW), anyString());
    }

    private MiddayReviewJob newJob() {
        return new MiddayReviewJob(
                marketDataService,
                tradingStateService,
                positionService,
                notificationFacade,
                schedulerLogService,
                orchestrationService,
                aiTaskService,
                candidateScanService,
                requestWriterService
        );
    }

    private CandidateResponse candidate(String symbol, String name, String theme, String score) {
        BigDecimal s = new BigDecimal(score);
        return new CandidateResponse(
                LocalDate.of(2026, 5, 11),
                symbol,
                name,
                s,
                theme + "；測試候選",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                theme,
                null,
                s,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
