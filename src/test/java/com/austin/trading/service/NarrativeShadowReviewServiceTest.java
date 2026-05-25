package com.austin.trading.service;

import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.KolThemeSignalDailySnapshotEntity;
import com.austin.trading.entity.NarrativeCandidateTrackingSeedEntity;
import com.austin.trading.entity.NarrativeShadowReviewEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.KolThemeSignalDailySnapshotRepository;
import com.austin.trading.repository.NarrativeCandidateTrackingSeedRepository;
import com.austin.trading.repository.NarrativeShadowReviewRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class NarrativeShadowReviewServiceTest {

    @Test
    void aggregateBuildsShadowOnlyDailyReviewWarningsAndTrackingSeeds() {
        LocalDate date = LocalDate.of(2026, 5, 21);
        KolThemeSignalDailySnapshotRepository snapshotRepo = mock(KolThemeSignalDailySnapshotRepository.class);
        CandidateStockRepository candidateRepo = mock(CandidateStockRepository.class);
        NarrativeShadowReviewRepository reviewRepo = mock(NarrativeShadowReviewRepository.class);
        NarrativeCandidateTrackingSeedRepository seedRepo = mock(NarrativeCandidateTrackingSeedRepository.class);
        NarrativeShadowObservationMetrics metrics = mock(NarrativeShadowObservationMetrics.class);
        NarrativeShadowReviewService service = new NarrativeShadowReviewService(
                snapshotRepo, candidateRepo, reviewRepo, seedRepo, new ObjectMapper(), metrics);

        when(snapshotRepo.findByTradingDateOrderByNetShadowBoostDesc(date)).thenReturn(List.of(
                snapshot(date, "AI power", "BULLISH", "0.91", "0.10", "0.1800", "LOW", 6, 12),
                snapshot(date, "機器人", "BULLISH", "0.88", "0.05", "0.1200", "HIGH", 5, 8),
                snapshot(date, "被動元件", "BULLISH", "0.72", "0.08", "0.1000", "LOW", 2, 5)
        ));
        when(candidateRepo.findByTradingDateOrderByScoreDesc(eq(date), any(Pageable.class))).thenReturn(List.of(
                candidate(date, "2308", "台達電", "AI power", "8.0000"),
                candidate(date, "4561", "健椿", "機器人", "7.7000"),
                candidate(date, "2327", "國巨", "被動元件", "7.2000")
        ));
        when(reviewRepo.save(any(NarrativeShadowReviewEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(seedRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.aggregate(date);

        assertThat(response.weakSignalOnly()).isTrue();
        assertThat(response.shadowOnly()).isTrue();
        assertThat(response.productionDecisionAllowed()).isFalse();
        assertThat(response.themeSummary()).extracting("theme")
                .containsExactly("AI power", "機器人", "被動元件");
        assertThat(response.lifecycleBoard()).containsEntry("EXPANDING", 1L)
                .containsEntry("CROWDED", 1L)
                .containsEntry("EMERGING", 1L);
        assertThat(response.candidateImpact()).hasSize(3);
        assertThat(response.candidateImpact()).anySatisfy(item -> {
            assertThat(item.symbol()).isEqualTo("4561");
            assertThat(item.riskFlags()).contains("DO_NOT_CHASE", "CROWDED_THEME");
            assertThat(item.crowdingFlags()).contains("NARRATIVE_OVERHEATED");
        });
        assertThat(response.warnings()).contains("DO_NOT_CHASE", "NARRATIVE_OVERHEATED", "CROWDED_THEME", "NEED_PULLBACK_CONFIRMATION");
        assertThat(response.rotationAnalysis().emergingThemes()).contains("被動元件");
        assertThat(response.rotationAnalysis().crowdedThemes()).contains("機器人");
        assertThat(response.rotationAnalysis().expandingThemes()).contains("AI power");
        assertThat(response.trackingSeeds()).hasSize(3);
        assertThat(response.metrics()).containsEntry("narrative_theme_emerging_total", 1L)
                .containsEntry("narrative_theme_crowded_total", 1L)
                .containsEntry("narrative_shadow_boost_total", 2L)
                .containsEntry("narrative_shadow_penalty_total", 1L)
                .containsEntry("narrative_fomo_warning_total", 1L)
                .containsEntry("narrative_candidate_tracking_total", 3L);
        verify(reviewRepo).deleteByTradingDate(date);
        verify(reviewRepo).flush();
        verify(seedRepo).deleteByDecisionDate(date);
        verify(seedRepo).flush();
        verify(seedRepo).saveAll(any());
        verify(metrics).publish(response.metrics());
    }

    @Test
    void reportReturnsPersistedReviewWhenPresent() {
        LocalDate date = LocalDate.of(2026, 5, 21);
        KolThemeSignalDailySnapshotRepository snapshotRepo = mock(KolThemeSignalDailySnapshotRepository.class);
        CandidateStockRepository candidateRepo = mock(CandidateStockRepository.class);
        NarrativeShadowReviewRepository reviewRepo = mock(NarrativeShadowReviewRepository.class);
        NarrativeCandidateTrackingSeedRepository seedRepo = mock(NarrativeCandidateTrackingSeedRepository.class);
        NarrativeShadowObservationMetrics metrics = mock(NarrativeShadowObservationMetrics.class);
        NarrativeShadowReviewService service = new NarrativeShadowReviewService(
                snapshotRepo, candidateRepo, reviewRepo, seedRepo, new ObjectMapper(), metrics);
        NarrativeShadowReviewEntity review = new NarrativeShadowReviewEntity();
        review.setTradingDate(date);
        review.setThemeSummaryJson("[]");
        review.setLifecycleBoardJson("{}");
        review.setCandidateImpactJson("[]");
        review.setWarningsJson("[]");
        review.setRotationAnalysisJson("{\"emergingThemes\":[],\"expandingThemes\":[],\"crowdedThemes\":[],\"exhaustedThemes\":[],\"rotationSummary\":\"no narrative rotation evidence\"}");
        review.setMetricsJson("{\"narrative_candidate_tracking_total\":1}");
        when(reviewRepo.findByTradingDate(date)).thenReturn(java.util.Optional.of(review));
        NarrativeCandidateTrackingSeedEntity seed = new NarrativeCandidateTrackingSeedEntity();
        seed.setDecisionDate(date);
        seed.setSymbol("2308");
        seed.setStockName("台達電");
        seed.setRelatedTheme("AI power");
        seed.setLifecycleAtDetection("EXPANDING");
        seed.setAttentionScore(new BigDecimal("9.1"));
        seed.setCrowdingScore(new BigDecimal("3.1"));
        seed.setShadowDelta(new BigDecimal("0.1800"));
        seed.setWeakSignalOnly(true);
        seed.setProductionDecisionAllowed(false);
        when(seedRepo.findByDecisionDateOrderByShadowDeltaDesc(date)).thenReturn(List.of(seed));

        var response = service.report(date);

        assertThat(response.trackingSeeds()).hasSize(1);
        assertThat(response.trackingSeeds().get(0).symbol()).isEqualTo("2308");
        verifyNoInteractions(snapshotRepo, candidateRepo);
    }

    private KolThemeSignalDailySnapshotEntity snapshot(LocalDate date, String theme, String direction,
                                                       String positive, String negative, String boost,
                                                       String crowdingRisk, int sourceCount, int evidenceCount) {
        KolThemeSignalDailySnapshotEntity e = new KolThemeSignalDailySnapshotEntity();
        e.setTradingDate(date);
        e.setThemeTag(theme);
        e.setDirection(direction);
        e.setPositiveScore(new BigDecimal(positive));
        e.setNegativeScore(new BigDecimal(negative));
        e.setNetShadowBoost(new BigDecimal(boost));
        e.setCrowdingRisk(crowdingRisk);
        e.setSourceCount(sourceCount);
        e.setEvidenceCount(evidenceCount);
        e.setTopSourcesJson("[\"股癌\"]");
        return e;
    }

    private CandidateStockEntity candidate(LocalDate date, String symbol, String name, String theme, String score) {
        CandidateStockEntity e = new CandidateStockEntity();
        e.setTradingDate(date);
        e.setSymbol(symbol);
        e.setStockName(name);
        e.setThemeTag(theme);
        e.setScore(new BigDecimal(score));
        return e;
    }
}
