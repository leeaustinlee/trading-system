package com.austin.trading.service;

import com.austin.trading.entity.ThemeReplayMetricsEntity;
import com.austin.trading.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReplayMetricsSafetyTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 22);
    private ThemeReplayMetricsRepository metricsRepository;
    private CandidateStockRepository candidateStockRepository;
    private FinalDecisionRepository finalDecisionRepository;
    private ReplayMetricsService service;

    @BeforeEach
    void setUp() {
        metricsRepository = mock(ThemeReplayMetricsRepository.class);
        candidateStockRepository = mock(CandidateStockRepository.class);
        finalDecisionRepository = mock(FinalDecisionRepository.class);
        service = new ReplayMetricsService(metricsRepository, mock(ThemeReplaySnapshotRepository.class), mock(ThemeReplayNodeRepository.class),
                mock(ThemeReplayEdgeRepository.class), mock(ThemeLifecycleStateRepository.class), mock(ResearchUniverseItemRepository.class),
                candidateStockRepository, finalDecisionRepository, new ObjectMapper());
    }

    @Test
    void safetySummaryRequiresAllForbiddenCountersZero() {
        ThemeReplayMetricsEntity metric = new ThemeReplayMetricsEntity();
        metric.setTradingDate(DATE);
        metric.setThemeTag("被動元件");
        metric.setRiskGateBypassCount(0);
        metric.setLeadershipOnlyEnteredCount(0);
        metric.setLeaderTradableFalseEnterCount(0);
        metric.setPeerShadowDirectPromotionCount(0);
        metric.setNarrativeDirectEnterCount(0);
        metric.setResearchVsTradableSeparationViolationCount(0);
        when(metricsRepository.findByTradingDateOrderByThemeTagAsc(DATE)).thenReturn(List.of(metric));

        var summary = service.safetySummary(DATE);

        assertThat(summary.replayOnly()).isTrue();
        assertThat(summary.analyticsOnly()).isTrue();
        assertThat(summary.noAutoPromotion()).isTrue();
        assertThat(summary.safetyViolationDetected()).isFalse();
        assertThat(summary.riskGateBypassCount()).isZero();
        assertThat(summary.leadershipOnlyEnteredCount()).isZero();
        assertThat(summary.leaderTradableFalseEnterCount()).isZero();
        assertThat(summary.researchVsTradableSeparationViolationCount()).isZero();
        assertThat(summary.safetyBoundary().doesNotAffectBuySellEnter()).isTrue();
        assertThat(summary.safetyBoundary().metricsDoNotOverrideRiskGate()).isTrue();
        verify(candidateStockRepository, never()).save(any());
        verify(finalDecisionRepository, never()).save(any());
    }

    @Test
    void responseItemSafetyBoundaryIsReplayAnalyticsOnly() {
        ThemeReplayMetricsEntity metric = new ThemeReplayMetricsEntity();
        metric.setTradingDate(DATE);
        metric.setThemeTag("被動元件");
        metric.setLeaderRetentionRate(BigDecimal.ONE);
        metric.setPeerDiscoveryHitRate(BigDecimal.ONE);
        metric.setCandidateDiversification(4);

        var item = service.toItem(metric);

        assertThat(item.safetyBoundary().replayOnly()).isTrue();
        assertThat(item.safetyBoundary().analyticsOnly()).isTrue();
        assertThat(item.safetyBoundary().doesNotAffectFinalDecision()).isTrue();
        assertThat(item.safetyBoundary().doesNotWriteCandidateStock()).isTrue();
        assertThat(item.safetyBoundary().noAutoPromotion()).isTrue();
        assertThat(item.riskGateBypassCount()).isZero();
        assertThat(item.leadershipOnlyEnteredCount()).isZero();
        assertThat(item.leaderTradableFalseEnterCount()).isZero();
    }
}
