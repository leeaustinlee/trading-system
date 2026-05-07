package com.austin.trading.integration;

import com.austin.trading.controller.DashboardController;
import com.austin.trading.domain.enums.TuningConfidence;
import com.austin.trading.domain.enums.TuningRecommendationStatus;
import com.austin.trading.domain.enums.TuningRecommendationType;
import com.austin.trading.dto.response.ScoreConfigResponse;
import com.austin.trading.dto.response.StrategyTuningSummaryDto;
import com.austin.trading.dto.response.TuningEvaluationResultDto;
import com.austin.trading.dto.response.TuningEvaluationSummaryDto;
import com.austin.trading.engine.TuningEvaluationEngine;
import com.austin.trading.engine.StrategyTuningEngine;
import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.MissedRallyTrackingEntity;
import com.austin.trading.entity.StrategyTuningRecommendationEntity;
import com.austin.trading.entity.TuningApplySnapshotEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.MissedRallyTrackingRepository;
import com.austin.trading.repository.StrategyTuningHistoryRepository;
import com.austin.trading.repository.StrategyTuningRecommendationRepository;
import com.austin.trading.repository.TuningAfterMetricsRepository;
import com.austin.trading.repository.TuningApplySnapshotRepository;
import com.austin.trading.repository.TuningEvaluationResultRepository;
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
        TuningApplySnapshotService snapshotService = mock(TuningApplySnapshotService.class);
        StrategyTuningService service = new StrategyTuningService(mock(StrategyTuningEngine.class), recRepo, historyRepo, config,
                snapshotService);
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
        verify(snapshotService).writeSnapshot(eq(rec), any(LocalDate.class));
    }

    @Test
    void dashboardSummaryShowsPendingCountAndEvaluationSummary() {
        StrategyTuningService tuning = mock(StrategyTuningService.class);
        TuningEvaluationQueryService evaluation = mock(TuningEvaluationQueryService.class);
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
        when(evaluation.getSummary()).thenReturn(new TuningEvaluationSummaryDto(1, 1, new BigDecimal("0.5000"),
                new TuningEvaluationResultDto(11L, 1L, "SUCCESS", "ok", BigDecimal.ONE, BigDecimal.ZERO, "KEEP", null),
                new TuningEvaluationResultDto(12L, 2L, "FAIL", "bad", BigDecimal.ZERO, BigDecimal.ONE, "ROLLBACK", null),
                1));
        DashboardController controller = new DashboardController(
                market,
                state,
                finalDecision,
                hourly,
                monitor,
                notification,
                candidate,
                tuning,
                evaluation
        );

        var response = controller.getCurrentDashboard();

        assertThat(response.pendingTuningRecommendationCount()).isEqualTo(2);
        assertThat(response.tuningWarningMessage()).isEqualTo("目前有 2 筆策略調參建議待審核");
        assertThat(response.tuningSuccessRate()).isEqualByComparingTo("0.5000");
        assertThat(response.lastTuningResult().evaluationStatus()).isEqualTo("FAIL");
        assertThat(response.rollbackSuggestionCount()).isEqualTo(1);
    }

    @Test
    void applyThenT5MetricsThenEvaluationProducesSuccessResult() {
        StrategyTuningRecommendationRepository recRepo = mock(StrategyTuningRecommendationRepository.class);
        StrategyTuningHistoryRepository historyRepo = mock(StrategyTuningHistoryRepository.class);
        CandidateForwardTrackingRepository candidateRepo = mock(CandidateForwardTrackingRepository.class);
        TuningApplySnapshotRepository snapshotRepo = mock(TuningApplySnapshotRepository.class);
        TuningAfterMetricsRepository afterRepo = mock(TuningAfterMetricsRepository.class);
        TuningEvaluationResultRepository resultRepo = mock(TuningEvaluationResultRepository.class);
        ScoreConfigService config = mock(ScoreConfigService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        TuningApplySnapshotService snapshotService = new TuningApplySnapshotService(candidateRepo, snapshotRepo, objectMapper);
        StrategyTuningService tuningService = new StrategyTuningService(mock(StrategyTuningEngine.class), recRepo, historyRepo,
                config, snapshotService);
        TuningAfterTrackingService afterService = new TuningAfterTrackingService(recRepo, snapshotRepo, afterRepo, candidateRepo);
        TuningEvaluationEngine evaluationEngine = new TuningEvaluationEngine(snapshotRepo, afterRepo, resultRepo, recRepo, objectMapper);
        StrategyTuningRecommendationEntity rec = recommendation(TuningRecommendationStatus.APPROVED);
        rec.setWinRate(new BigDecimal("0.50"));
        rec.setAvgReturnPct(new BigDecimal("1.0"));
        rec.setAvgMfePct(new BigDecimal("4.0"));
        rec.setAvgMaePct(new BigDecimal("-2.0"));
        when(recRepo.findById(1L)).thenReturn(Optional.of(rec));
        when(recRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(historyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(snapshotRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(afterRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(resultRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(config.getByKey("scoring.enter_min_score"))
                .thenReturn(new ScoreConfigResponse(1L, "scoring.enter_min_score", "6.5", "DECIMAL", "", null));
        when(candidateRepo.findByTradingDateBetween(any(), any())).thenReturn(beforeRows(10));

        tuningService.applyApprovedRecommendation(1L, "Austin");
        rec.setAppliedAt(java.time.LocalDateTime.of(2026, 5, 1, 9, 0));
        TuningApplySnapshotEntity snapshot = new TuningApplySnapshotEntity();
        snapshot.setRecommendationId(1L);
        snapshot.setDecisionWinRate(new BigDecimal("0.5000"));
        snapshot.setDecisionAvgReturn(new BigDecimal("1.0000"));
        snapshot.setDecisionAvgMfe(new BigDecimal("4.0000"));
        snapshot.setDecisionAvgMae(new BigDecimal("-2.0000"));
        snapshot.setStrategyMetricsJson("{\"sampleSize\":10}");
        when(snapshotRepo.findByRecommendationId(1L)).thenReturn(Optional.of(snapshot));
        when(afterRepo.findByRecommendationIdAndHorizonDays(1L, 5)).thenReturn(Optional.empty());
        when(candidateRepo.findByTradingDateGreaterThanEqual(LocalDate.of(2026, 5, 1))).thenReturn(afterRows(10));

        var metrics = afterService.calculateDueMetrics(rec, LocalDate.of(2026, 5, 6));
        when(afterRepo.findByRecommendationIdOrderByHorizonDaysDesc(1L)).thenReturn(metrics);
        var result = evaluationEngine.evaluate(1L);

        assertThat(metrics).hasSize(1);
        assertThat(result.getEvaluationStatus().name()).isEqualTo("SUCCESS");
        verify(config).update("scoring.enter_min_score", "6.8");
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

    private List<CandidateForwardTrackingEntity> beforeRows(int count) {
        List<CandidateForwardTrackingEntity> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CandidateForwardTrackingEntity e = new CandidateForwardTrackingEntity();
            e.setTradingDate(LocalDate.of(2026, 4, 1).plusDays(i));
            e.setFinalDecision("ENTER");
            e.setT5CloseReturnPct(i % 2 == 0 ? new BigDecimal("2.0") : BigDecimal.ZERO);
            e.setMfePct(new BigDecimal("4.0"));
            e.setMaePct(new BigDecimal("-2.0"));
            e.setRelativeReturnPct(new BigDecimal("0.2"));
            rows.add(e);
        }
        return rows;
    }

    private List<CandidateForwardTrackingEntity> afterRows(int count) {
        List<CandidateForwardTrackingEntity> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CandidateForwardTrackingEntity e = new CandidateForwardTrackingEntity();
            e.setTradingDate(LocalDate.of(2026, 5, 1).plusDays(i % 5));
            e.setFinalDecision("ENTER");
            e.setT5CloseReturnPct(i < 6 ? new BigDecimal("3.0") : new BigDecimal("1.0"));
            e.setMfePct(new BigDecimal("5.0"));
            e.setMaePct(new BigDecimal("-2.4"));
            e.setRelativeReturnPct(new BigDecimal("0.4"));
            e.setBenchmarkReturnPct(new BigDecimal("0.8"));
            rows.add(e);
        }
        return rows;
    }
}
