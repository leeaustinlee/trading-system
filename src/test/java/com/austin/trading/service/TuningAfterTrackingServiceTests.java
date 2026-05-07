package com.austin.trading.service;

import com.austin.trading.domain.enums.TuningRecommendationStatus;
import com.austin.trading.domain.enums.TuningRecommendationType;
import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.StrategyTuningRecommendationEntity;
import com.austin.trading.entity.TuningApplySnapshotEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.StrategyTuningRecommendationRepository;
import com.austin.trading.repository.TuningAfterMetricsRepository;
import com.austin.trading.repository.TuningApplySnapshotRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TuningAfterTrackingServiceTests {
    @Test
    void calculateDueMetricsWritesT5MetricsFromForwardTracking() {
        StrategyTuningRecommendationRepository recRepo = mock(StrategyTuningRecommendationRepository.class);
        TuningApplySnapshotRepository snapshotRepo = mock(TuningApplySnapshotRepository.class);
        TuningAfterMetricsRepository metricsRepo = mock(TuningAfterMetricsRepository.class);
        CandidateForwardTrackingRepository candidateRepo = mock(CandidateForwardTrackingRepository.class);
        TuningAfterTrackingService service = new TuningAfterTrackingService(recRepo, snapshotRepo, metricsRepo, candidateRepo);
        StrategyTuningRecommendationEntity rec = recommendation();
        when(snapshotRepo.findByRecommendationId(1L)).thenReturn(Optional.of(new TuningApplySnapshotEntity()));
        when(metricsRepo.findByRecommendationIdAndHorizonDays(anyLong(), anyInt())).thenReturn(Optional.empty());
        when(metricsRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(candidateRepo.findByTradingDateGreaterThanEqual(LocalDate.of(2026, 5, 1)))
                .thenReturn(List.of(row(1, "2.0"), row(2, "-1.0"), row(3, "3.0"), row(4, "4.0"), row(5, "-2.0")));

        var written = service.calculateDueMetrics(rec, LocalDate.of(2026, 5, 6));

        assertThat(written).hasSize(1);
        assertThat(written.get(0).getHorizonDays()).isEqualTo(5);
        assertThat(written.get(0).getSampleSize()).isEqualTo(5);
        assertThat(written.get(0).getWinRate()).isEqualByComparingTo("0.6000");
        assertThat(written.get(0).getAvgReturn()).isEqualByComparingTo("1.2000");
    }

    private StrategyTuningRecommendationEntity recommendation() {
        StrategyTuningRecommendationEntity e = new StrategyTuningRecommendationEntity();
        e.setId(1L);
        e.setGeneratedDate(LocalDate.of(2026, 4, 30));
        e.setLookbackDays(20);
        e.setRecommendationType(TuningRecommendationType.THRESHOLD_TIGHTEN);
        e.setTargetParameter("scoring.enter_min_score");
        e.setStatus(TuningRecommendationStatus.APPLIED);
        e.setAppliedAt(LocalDateTime.of(2026, 5, 1, 9, 0));
        return e;
    }

    private CandidateForwardTrackingEntity row(int day, String t5) {
        CandidateForwardTrackingEntity e = new CandidateForwardTrackingEntity();
        e.setTradingDate(LocalDate.of(2026, 5, day));
        e.setFinalDecision("ENTER");
        e.setT5CloseReturnPct(new BigDecimal(t5));
        e.setMfePct(new BigDecimal("5.0"));
        e.setMaePct(new BigDecimal("-2.0"));
        e.setRelativeReturnPct(new BigDecimal("0.5"));
        e.setBenchmarkReturnPct(new BigDecimal("0.7"));
        return e;
    }
}
