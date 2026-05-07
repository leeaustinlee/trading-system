package com.austin.trading.service;

import com.austin.trading.domain.enums.TuningConfidence;
import com.austin.trading.domain.enums.TuningRecommendationStatus;
import com.austin.trading.domain.enums.TuningRecommendationType;
import com.austin.trading.dto.response.ScoreConfigResponse;
import com.austin.trading.engine.StrategyTuningEngine;
import com.austin.trading.entity.StrategyTuningHistoryEntity;
import com.austin.trading.entity.StrategyTuningRecommendationEntity;
import com.austin.trading.repository.StrategyTuningHistoryRepository;
import com.austin.trading.repository.StrategyTuningRecommendationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StrategyTuningServiceTests {
    private StrategyTuningEngine engine;
    private StrategyTuningRecommendationRepository recommendationRepository;
    private StrategyTuningHistoryRepository historyRepository;
    private ScoreConfigService scoreConfigService;
    private TuningApplySnapshotService tuningApplySnapshotService;
    private StrategyTuningService service;

    @BeforeEach
    void setUp() {
        engine = mock(StrategyTuningEngine.class);
        recommendationRepository = mock(StrategyTuningRecommendationRepository.class);
        historyRepository = mock(StrategyTuningHistoryRepository.class);
        scoreConfigService = mock(ScoreConfigService.class);
        tuningApplySnapshotService = mock(TuningApplySnapshotService.class);
        service = new StrategyTuningService(engine, recommendationRepository, historyRepository, scoreConfigService,
                tuningApplySnapshotService);
        when(recommendationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(historyRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void approveOnlyChangesStatusAndDoesNotApplyConfig() {
        var rec = recommendation(TuningRecommendationStatus.PENDING);
        when(recommendationRepository.findById(1L)).thenReturn(Optional.of(rec));
        var dto = service.approveRecommendation(1L, "Austin");
        assertThat(dto.status()).isEqualTo("APPROVED");
        verify(scoreConfigService, never()).update(anyString(), anyString());
        verify(historyRepository, never()).save(any());
    }

    @Test
    void applyApprovedRecommendation_updatesConfigAndWritesHistory() {
        var rec = recommendation(TuningRecommendationStatus.APPROVED);
        when(recommendationRepository.findById(1L)).thenReturn(Optional.of(rec));
        when(scoreConfigService.getByKey("scoring.enter_min_score"))
                .thenReturn(new ScoreConfigResponse(1L, "scoring.enter_min_score", "6.5", "DECIMAL", "", null));

        var dto = service.applyApprovedRecommendation(1L, "Austin");

        assertThat(dto.status()).isEqualTo("APPLIED");
        assertThat(dto.rollbackValue()).isEqualTo("6.5");
        verify(scoreConfigService).update("scoring.enter_min_score", "6.8");
        verify(historyRepository).save(any(StrategyTuningHistoryEntity.class));
        verify(tuningApplySnapshotService).writeSnapshot(eq(rec), any(LocalDate.class));
    }

    @Test
    void rollbackAppliedRecommendation_restoresRollbackValueAndKeepsHistory() {
        var rec = recommendation(TuningRecommendationStatus.APPLIED);
        rec.setRollbackValue("6.5");
        when(recommendationRepository.findById(1L)).thenReturn(Optional.of(rec));
        when(scoreConfigService.getByKey("scoring.enter_min_score"))
                .thenReturn(new ScoreConfigResponse(1L, "scoring.enter_min_score", "6.8", "DECIMAL", "", null));

        var dto = service.rollbackRecommendation(1L, "Austin");

        assertThat(dto.status()).isEqualTo("ROLLED_BACK");
        verify(scoreConfigService).update("scoring.enter_min_score", "6.5");
        verify(historyRepository).save(any(StrategyTuningHistoryEntity.class));
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
}
