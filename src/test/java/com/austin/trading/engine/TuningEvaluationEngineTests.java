package com.austin.trading.engine;

import com.austin.trading.domain.enums.*;
import com.austin.trading.entity.StrategyTuningRecommendationEntity;
import com.austin.trading.entity.TuningAfterMetricsEntity;
import com.austin.trading.entity.TuningApplySnapshotEntity;
import com.austin.trading.repository.StrategyTuningRecommendationRepository;
import com.austin.trading.repository.TuningAfterMetricsRepository;
import com.austin.trading.repository.TuningApplySnapshotRepository;
import com.austin.trading.repository.TuningEvaluationResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TuningEvaluationEngineTests {
    private TuningApplySnapshotRepository snapshotRepo;
    private TuningAfterMetricsRepository afterRepo;
    private TuningEvaluationResultRepository resultRepo;
    private StrategyTuningRecommendationRepository recRepo;
    private TuningEvaluationEngine engine;

    @BeforeEach
    void setUp() {
        snapshotRepo = mock(TuningApplySnapshotRepository.class);
        afterRepo = mock(TuningAfterMetricsRepository.class);
        resultRepo = mock(TuningEvaluationResultRepository.class);
        recRepo = mock(StrategyTuningRecommendationRepository.class);
        engine = new TuningEvaluationEngine(snapshotRepo, afterRepo, resultRepo, recRepo, new ObjectMapper());
        when(resultRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(recRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void missingSnapshotIsInsufficientData() {
        when(snapshotRepo.findByRecommendationId(1L)).thenReturn(Optional.empty());

        var result = engine.evaluate(1L);

        assertThat(result.getEvaluationStatus()).isEqualTo(TuningEvaluationStatus.INSUFFICIENT_DATA);
        verify(recRepo, never()).save(any());
    }

    @Test
    void insufficientSampleCannotBeSuccess() {
        when(snapshotRepo.findByRecommendationId(1L)).thenReturn(Optional.of(snapshot(10)));
        when(afterRepo.findByRecommendationIdOrderByHorizonDaysDesc(1L)).thenReturn(List.of(after(5, 1, "10", "1", "-1")));

        var result = engine.evaluate(1L);

        assertThat(result.getEvaluationStatus()).isEqualTo(TuningEvaluationStatus.INSUFFICIENT_DATA);
    }

    @Test
    void classifiesSuccessWhenReturnWinRateImproveAndMaeStable() {
        when(snapshotRepo.findByRecommendationId(1L)).thenReturn(Optional.of(snapshot(10)));
        when(afterRepo.findByRecommendationIdOrderByHorizonDaysDesc(1L)).thenReturn(List.of(after(5, 10, "2.2", "0.58", "-2.5")));

        var result = engine.evaluate(1L);

        assertThat(result.getEvaluationStatus()).isEqualTo(TuningEvaluationStatus.SUCCESS);
        assertThat(result.getFinalDecision()).isEqualTo(TuningFinalDecision.KEEP);
    }

    @Test
    void failCreatesPendingRollbackSuggestionWithoutAutoRollback() {
        when(snapshotRepo.findByRecommendationId(1L)).thenReturn(Optional.of(snapshot(10)));
        when(afterRepo.findByRecommendationIdOrderByHorizonDaysDesc(1L)).thenReturn(List.of(after(5, 10, "-0.5", "0.35", "-5.5")));
        when(recRepo.findById(1L)).thenReturn(Optional.of(original()));

        var result = engine.evaluate(1L);

        assertThat(result.getEvaluationStatus()).isEqualTo(TuningEvaluationStatus.FAIL);
        verify(recRepo).save(argThat(s -> s.getRecommendationType() == TuningRecommendationType.ROLLBACK_SUGGESTION
                && s.getStatus() == TuningRecommendationStatus.PENDING
                && "6.5".equals(s.getSuggestedValue())
                && "6.8".equals(s.getRollbackValue())));
    }

    private TuningApplySnapshotEntity snapshot(int sample) {
        TuningApplySnapshotEntity e = new TuningApplySnapshotEntity();
        e.setRecommendationId(1L);
        e.setDecisionAvgReturn(new BigDecimal("1.0"));
        e.setDecisionWinRate(new BigDecimal("0.50"));
        e.setDecisionAvgMfe(new BigDecimal("4.0"));
        e.setDecisionAvgMae(new BigDecimal("-2.0"));
        e.setStrategyMetricsJson("{\"sampleSize\":" + sample + "}");
        return e;
    }

    private TuningAfterMetricsEntity after(int horizon, int sample, String avgReturn, String winRate, String avgMae) {
        TuningAfterMetricsEntity e = new TuningAfterMetricsEntity();
        e.setRecommendationId(1L);
        e.setHorizonDays(horizon);
        e.setSampleSize(sample);
        e.setAvgReturn(new BigDecimal(avgReturn));
        e.setWinRate(new BigDecimal(winRate));
        e.setAvgMae(new BigDecimal(avgMae));
        e.setAvgMfe(new BigDecimal("5.0"));
        e.setAvgRelativeReturn(new BigDecimal("0.5"));
        return e;
    }

    private StrategyTuningRecommendationEntity original() {
        StrategyTuningRecommendationEntity e = new StrategyTuningRecommendationEntity();
        e.setId(1L);
        e.setGeneratedDate(LocalDate.of(2026, 5, 1));
        e.setLookbackDays(20);
        e.setRecommendationType(TuningRecommendationType.THRESHOLD_TIGHTEN);
        e.setTargetModule("scoring");
        e.setTargetParameter("scoring.enter_min_score");
        e.setSuggestedValue("6.8");
        e.setRollbackValue("6.5");
        e.setConfidence(TuningConfidence.MEDIUM);
        return e;
    }
}
