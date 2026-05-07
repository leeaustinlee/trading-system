package com.austin.trading.integration;

import com.austin.trading.controller.DashboardController;
import com.austin.trading.domain.enums.TuningConfidence;
import com.austin.trading.domain.enums.TuningRecommendationStatus;
import com.austin.trading.domain.enums.TuningRecommendationType;
import com.austin.trading.dto.response.ScoreConfigResponse;
import com.austin.trading.dto.response.StrategyTuningSummaryDto;
import com.austin.trading.engine.StrategyTuningEngine;
import com.austin.trading.entity.MissedRallyTrackingEntity;
import com.austin.trading.entity.StrategyTuningRecommendationEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.MissedRallyTrackingRepository;
import com.austin.trading.repository.StrategyTuningHistoryRepository;
import com.austin.trading.repository.StrategyTuningRecommendationRepository;
import com.austin.trading.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StrategyTuningFlowIntegrationTests {

    @Test
    void forwardTrackingSample_generatesRecommendation() {
        CandidateForwardTrackingRepository candidateRepo = mock(CandidateForwardTrackingRepository.class);
        MissedRallyTrackingRepository missedRepo = mock(MissedRallyTrackingRepository.class);
        when(candidateRepo.findByTradingDateBetween(any(), any())).thenReturn(List.of());
        when(missedRepo.findByTradingDateBetween(any(), any()))
                .thenReturn(missedRows(25, 10, "REJECT", "near_day_high", "BREAKOUT"));

        StrategyTuningEngine engine = new StrategyTuningEngine(candidateRepo, missedRepo, new ObjectMapper());
        var recs = engine.generateRecommendations(LocalDate.of(2026, 5, 7), 20);

        assertThat(recs).anyMatch(r -> "gate.near_day_high_reject_threshold".equals(r.getTargetParameter()));
        assertThat(recs).allMatch(r -> r.getStatus() == TuningRecommendationStatus.PENDING);
    }

    @Test
    void recommendationApproveApplyThenRollback_updatesAndRestoresConfig() {
        StrategyTuningRecommendationRepository recRepo = mock(StrategyTuningRecommendationRepository.class);
        StrategyTuningHistoryRepository historyRepo = mock(StrategyTuningHistoryRepository.class);
        ScoreConfigService config = mock(ScoreConfigService.class);
        StrategyTuningService service = new StrategyTuningService(mock(StrategyTuningEngine.class), recRepo, historyRepo, config);
        StrategyTuningRecommendationEntity rec = recommendation(TuningRecommendationStatus.PENDING);

        when(recRepo.findById(1L)).thenReturn(Optional.of(rec));
        when(recRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(config.getByKey("scoring.enter_min_score"))
                .thenReturn(new ScoreConfigResponse(1L, "scoring.enter_min_score", "6.5", "DECIMAL", "", null))
                .thenReturn(new ScoreConfigResponse(1L, "scoring.enter_min_score", "6.8", "DECIMAL", "", null));

        assertThat(service.approveRecommendation(1L, "Austin").status()).isEqualTo("APPROVED");
        assertThat(service.applyApprovedRecommendation(1L, "Austin").status()).isEqualTo("APPLIED");
        assertThat(service.rollbackRecommendation(1L, "Austin").status()).isEqualTo("ROLLED_BACK");

        verify(config).update("scoring.enter_min_score", "6.8");
        verify(config).update("scoring.enter_min_score", "6.5");
        verify(historyRepo, times(2)).save(any());
    }

    @Test
    void dashboardSummaryShowsPendingCount() {
        StrategyTuningService tuning = mock(StrategyTuningService.class);
        MarketDataService market = mock(MarketDataService.class);
        TradingStateService state = mock(TradingStateService.class);
        FinalDecisionService finalDecision = mock(FinalDecisionService.class);
        HourlyGateDecisionService hourly = mock(HourlyGateDecisionService.class);
        MonitorDecisionService monitor = mock(MonitorDecisionService.class);
        NotificationService notification = mock(NotificationService.class);
        CandidateScanService candidate = mock(CandidateScanService.class);
        when(market.getMarketPreferToday()).thenReturn(Optional.empty());
        when(state.getCurrentState()).thenReturn(Optional.empty());
        when(finalDecision.getCurrent()).thenReturn(Optional.empty());
        when(hourly.getCurrent()).thenReturn(Optional.empty());
        when(monitor.getCurrent()).thenReturn(Optional.empty());
        when(notification.getLatestNotification()).thenReturn(Optional.empty());
        when(candidate.getCurrentCandidates(5)).thenReturn(List.of());
        when(tuning.getTuningSummary()).thenReturn(new StrategyTuningSummaryDto(2, null, "目前有 2 筆策略調參建議待審核"));
        DashboardController controller = new DashboardController(
                market,
                state,
                finalDecision,
                hourly,
                monitor,
                notification,
                candidate,
                tuning
        );

        var response = controller.getCurrentDashboard();

        assertThat(response.pendingTuningRecommendationCount()).isEqualTo(2);
        assertThat(response.tuningWarningMessage()).isEqualTo("目前有 2 筆策略調參建議待審核");
    }

    private StrategyTuningRecommendationEntity recommendation(TuningRecommendationStatus status) {
        StrategyTuningRecommendationEntity e = new StrategyTuningRecommendationEntity();
        e.setId(1L);
        e.setGeneratedDate(LocalDate.of(2026, 5, 7));
        e.setLookbackDays(20);
        e.setRecommendationType(TuningRecommendationType.THRESHOLD_TIGHTEN);
        e.setTargetModule("scoring");
        e.setTargetParameter("scoring.enter_min_score");
        e.setCurrentValue("6.5");
        e.setSuggestedValue("6.8");
        e.setConfidence(TuningConfidence.MEDIUM);
        e.setStatus(status);
        return e;
    }

    private List<MissedRallyTrackingEntity> missedRows(int count, int missed, String decision, String gate, String strategy) {
        List<MissedRallyTrackingEntity> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MissedRallyTrackingEntity e = new MissedRallyTrackingEntity();
            e.setTradingDate(LocalDate.of(2026, 5, 7).minusDays(i + 6L));
            e.setOriginalDecision(decision);
            e.setPrimaryStrategy(strategy);
            e.setGateName(gate);
            e.setMaxReturnPct(new BigDecimal("8.0"));
            e.setMfePct(new BigDecimal("8.0"));
            e.setMaePct(new BigDecimal("-2.0"));
            e.setCloseReturnPct(new BigDecimal("4.0"));
            e.setMissedRallyFlag(i < missed);
            rows.add(e);
        }
        return rows;
    }
}
