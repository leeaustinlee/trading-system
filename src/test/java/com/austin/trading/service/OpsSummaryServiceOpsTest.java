package com.austin.trading.service;

import com.austin.trading.dto.response.ThemeReplayMetricsResponse;
import com.austin.trading.entity.SystemBuildTraceEntity;
import com.austin.trading.repository.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OpsSummaryServiceOpsTest {
    @Test
    void dailySummaryExposesCountsBuildStatusAndForbiddenCounters() {
        LocalDate date = LocalDate.of(2026, 5, 25);
        ThemeReplaySnapshotRepository replay = mock(ThemeReplaySnapshotRepository.class);
        ThemeLifecycleStateRepository lifecycle = mock(ThemeLifecycleStateRepository.class);
        ResearchUniverseItemRepository research = mock(ResearchUniverseItemRepository.class);
        HotGroupRadarSnapshotRepository hot = mock(HotGroupRadarSnapshotRepository.class);
        PromotionReviewItemRepository promotion = mock(PromotionReviewItemRepository.class);
        ThemeReplayMetricsRepository metricsRepo = mock(ThemeReplayMetricsRepository.class);
        ReplayMetricsService metricsService = mock(ReplayMetricsService.class);
        SystemBuildTraceRepository traces = mock(SystemBuildTraceRepository.class);

        when(replay.countByTradingDate(date)).thenReturn(7L);
        when(lifecycle.countByTradingDate(date)).thenReturn(7L);
        when(research.countByTradingDate(date)).thenReturn(10L);
        when(hot.findByTradingDateAndSourcePhaseOrderByHotScoreDesc(date, "POSTMARKET")).thenReturn(java.util.List.of());
        when(promotion.countByTradingDate(date)).thenReturn(28L);
        when(metricsRepo.countByTradingDate(date)).thenReturn(7L);
        when(metricsService.safetySummary(date)).thenReturn(new ThemeReplayMetricsResponse.SafetySummary(date, 7, 0, 0, 0, 0, 0, 0,
                false, ThemeReplayMetricsResponse.SafetyBoundary.replayAnalyticsOnlyBoundary(), true, true, true));
        when(traces.findByTradingDateOrderByStartedAtDescIdDesc(date)).thenReturn(java.util.List.of());

        var summary = new OpsSummaryService(replay, lifecycle, research, hot, promotion, metricsRepo, metricsService, traces).dailySummary(date);

        assertThat(summary).containsEntry("replayThemesCount", 7L);
        assertThat(summary).containsEntry("researchUniverseCount", 10L);
        assertThat(summary).containsEntry("promotionReviewCount", 28L);
        assertThat(summary).containsEntry("safetyViolationsCount", 0);
        assertThat(summary.get("forbiddenCountersSummary").toString()).contains("riskGateBypassCount=0");
    }

    @Test
    void themeFreshnessIncludesLatestBuildTraceMetadata() {
        LocalDate date = LocalDate.of(2026, 5, 27);
        ThemeReplaySnapshotRepository replay = mock(ThemeReplaySnapshotRepository.class);
        ThemeLifecycleStateRepository lifecycle = mock(ThemeLifecycleStateRepository.class);
        ResearchUniverseItemRepository research = mock(ResearchUniverseItemRepository.class);
        HotGroupRadarSnapshotRepository hot = mock(HotGroupRadarSnapshotRepository.class);
        PromotionReviewItemRepository promotion = mock(PromotionReviewItemRepository.class);
        ThemeReplayMetricsRepository metricsRepo = mock(ThemeReplayMetricsRepository.class);
        ReplayMetricsService metricsService = mock(ReplayMetricsService.class);
        SystemBuildTraceRepository traces = mock(SystemBuildTraceRepository.class);

        when(replay.countByTradingDate(date)).thenReturn(3L);
        when(replay.findAll()).thenReturn(java.util.List.of());
        when(lifecycle.countByTradingDate(date)).thenReturn(0L);
        when(lifecycle.findAll()).thenReturn(java.util.List.of());
        when(research.countByTradingDate(date)).thenReturn(0L);
        when(research.findAll()).thenReturn(java.util.List.of());
        when(hot.countByTradingDateAndSourcePhase(date, "POSTMARKET")).thenReturn(0L);
        when(hot.findAll()).thenReturn(java.util.List.of());
        when(promotion.countByTradingDate(date)).thenReturn(0L);
        when(metricsRepo.countByTradingDate(date)).thenReturn(0L);
        when(metricsRepo.findAll()).thenReturn(java.util.List.of());

        SystemBuildTraceEntity trace = new SystemBuildTraceEntity();
        trace.setStatus("SUCCESS");
        trace.setStartedAt(java.time.LocalDateTime.of(2026, 5, 27, 15, 25));
        trace.setFinishedAt(java.time.LocalDateTime.of(2026, 5, 27, 15, 26));
        trace.setDurationMs(60000L);
        trace.setInsertedCount(3);
        trace.setDeletedCount(1);
        when(traces.findByTradingDateAndBuildTypeOrderByStartedAtDescIdDesc(date, "THEME_REPLAY")).thenReturn(java.util.List.of(trace));

        var freshness = new OpsSummaryService(replay, lifecycle, research, hot, promotion, metricsRepo, metricsService, traces).themeFreshness(date);
        @SuppressWarnings("unchecked")
        var layers = (java.util.List<java.util.Map<String, Object>>) freshness.get("layers");
        var replayLayer = layers.stream().filter(x -> "ThemeReplay".equals(x.get("layer"))).findFirst().orElseThrow();

        assertThat(replayLayer).containsEntry("latestBuildStatus", "SUCCESS")
                .containsEntry("latestBuildDurationMs", 60000L)
                .containsEntry("latestBuildInsertedCount", 3)
                .containsEntry("latestBuildDeletedCount", 1);
    }
}
