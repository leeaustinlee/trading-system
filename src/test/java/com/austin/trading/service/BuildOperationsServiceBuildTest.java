package com.austin.trading.service;

import com.austin.trading.dto.response.BuildOperationResponse;
import com.austin.trading.dto.response.HotGroupRadarResponse;
import com.austin.trading.dto.response.PromotionReviewResponse;
import com.austin.trading.dto.response.ResearchUniverseResponse;
import com.austin.trading.dto.response.ThemeLifecycleResponse;
import com.austin.trading.dto.response.ThemeReplayMetricsResponse;
import com.austin.trading.dto.response.ThemeReplayTimelineResponse;
import com.austin.trading.entity.SystemBuildTraceEntity;
import com.austin.trading.repository.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BuildOperationsServiceBuildTest {
    @Test
    void themeReplayBuildResponseIsRebuildSafeAndSafetyBounded() {
        LocalDate date = LocalDate.of(2026, 5, 25);
        ThemeReplayTimelineService replay = mock(ThemeReplayTimelineService.class);
        ResearchUniverseService research = mock(ResearchUniverseService.class);
        ThemeLifecycleEngine lifecycle = mock(ThemeLifecycleEngine.class);
        ReplayMetricsService metrics = mock(ReplayMetricsService.class);
        HotGroupRadarService hot = mock(HotGroupRadarService.class);
        PromotionReviewService promotion = mock(PromotionReviewService.class);
        SystemBuildTraceService traces = mock(SystemBuildTraceService.class);
        when(traces.start(anyString(), eq(date), any(), any())).thenReturn(new SystemBuildTraceEntity());
        when(replay.build(date)).thenReturn(new ThemeReplayTimelineResponse(date, null, null, null, java.util.List.of(), java.util.List.of(), java.util.List.of(), null, null, null, java.util.List.of(), java.util.List.of(), com.austin.trading.dto.response.ThemeReplayMetricsResponse.MetricsSummary.empty(), ThemeReplayTimelineResponse.SafetyBoundary.replayOnlyBoundary(), true, true));

        ThemeReplaySnapshotRepository snapshots = mock(ThemeReplaySnapshotRepository.class);
        ThemeReplayNodeRepository nodes = mock(ThemeReplayNodeRepository.class);
        ThemeReplayEdgeRepository edges = mock(ThemeReplayEdgeRepository.class);
        when(snapshots.countByTradingDate(date)).thenReturn(2L, 3L, 3L);
        when(nodes.countByTradingDate(date)).thenReturn(4L, 5L, 5L);
        when(edges.countByTradingDate(date)).thenReturn(1L, 2L, 2L);

        BuildOperationsService service = new BuildOperationsService(replay, research, lifecycle, metrics, hot, promotion, traces,
                snapshots, nodes, edges, mock(ResearchUniverseItemRepository.class), mock(ThemeLifecycleStateRepository.class),
                mock(ThemeReplayMetricsRepository.class), mock(HotGroupRadarSnapshotRepository.class), mock(HotGroupStockSignalRepository.class),
                mock(CandidateThemeRadarTraceRepository.class), mock(PromotionReviewItemRepository.class), mock(PromotionReviewAuditRepository.class));

        BuildOperationResponse response = service.buildThemeReplay(date);

        assertThat(response.buildType()).isEqualTo("THEME_REPLAY");
        assertThat(response.rebuild()).isTrue();
        assertThat(response.deletedCount()).isEqualTo(7);
        assertThat(response.builtCount()).isEqualTo(10);
        assertThat(response.doesNotAffectFinalDecision()).isTrue();
        assertThat(response.doesNotAffectBuySellEnter()).isTrue();
        assertThat(response.doesNotWriteCandidateStock()).isTrue();
        assertThat(response.noAutoPromotion()).isTrue();
        verify(traces).success(isNull(), eq(7), eq(10), eq(0), eq(0), any(Map.class));
    }

    @Test
    void promotionReviewBuildResponseReportsManualPreservationCounts() {
        LocalDate date = LocalDate.of(2026, 5, 25);
        ThemeReplayTimelineService replay = mock(ThemeReplayTimelineService.class);
        ResearchUniverseService research = mock(ResearchUniverseService.class);
        ThemeLifecycleEngine lifecycle = mock(ThemeLifecycleEngine.class);
        ReplayMetricsService metrics = mock(ReplayMetricsService.class);
        HotGroupRadarService hot = mock(HotGroupRadarService.class);
        PromotionReviewService promotion = mock(PromotionReviewService.class);
        SystemBuildTraceService traces = mock(SystemBuildTraceService.class);
        when(traces.start(anyString(), eq(date), any(), any())).thenReturn(new SystemBuildTraceEntity());
        when(promotion.safetyBoundary()).thenReturn(PromotionReviewResponse.defaultSafetyBoundary());
        when(promotion.rebuild(date)).thenReturn(PromotionReviewResponse.of(date, List.of(), 2, 1, 4));
        PromotionReviewItemRepository itemRepo = mock(PromotionReviewItemRepository.class);
        PromotionReviewAuditRepository auditRepo = mock(PromotionReviewAuditRepository.class);
        when(itemRepo.countByTradingDate(date)).thenReturn(6L);
        when(auditRepo.countByTradingDate(date)).thenReturn(8L);

        BuildOperationsService service = new BuildOperationsService(replay, research, lifecycle, metrics, hot, promotion, traces,
                mock(ThemeReplaySnapshotRepository.class), mock(ThemeReplayNodeRepository.class), mock(ThemeReplayEdgeRepository.class),
                mock(ResearchUniverseItemRepository.class), mock(ThemeLifecycleStateRepository.class), mock(ThemeReplayMetricsRepository.class),
                mock(HotGroupRadarSnapshotRepository.class), mock(HotGroupStockSignalRepository.class), mock(CandidateThemeRadarTraceRepository.class),
                itemRepo, auditRepo);

        BuildOperationResponse response = service.buildPromotionReview(date);

        assertThat(response.buildType()).isEqualTo("PROMOTION_REVIEW");
        assertThat(response.manualReviewPreserved()).isTrue();
        assertThat(response.preservedManualCount()).isEqualTo(2);
        assertThat(response.mergedManualCount()).isEqualTo(1);
        assertThat(response.deletedSystemCount()).isEqualTo(4);
        assertThat(response.payload()).containsEntry("manualReviewPreserved", true)
                .containsEntry("preservedManualCount", 2)
                .containsEntry("mergedManualCount", 1)
                .containsEntry("deletedSystemCount", 4);
        verify(traces).success(isNull(), eq(14), eq(0), eq(0), eq(0), any(Map.class));
    }

    @Test
    void dailyThemeOpsBuildWritesTraceableLayers() {
        LocalDate date = LocalDate.of(2026, 5, 27);
        ThemeReplayTimelineService replay = mock(ThemeReplayTimelineService.class);
        ResearchUniverseService research = mock(ResearchUniverseService.class);
        ThemeLifecycleEngine lifecycle = mock(ThemeLifecycleEngine.class);
        ReplayMetricsService metrics = mock(ReplayMetricsService.class);
        HotGroupRadarService hot = mock(HotGroupRadarService.class);
        PromotionReviewService promotion = mock(PromotionReviewService.class);
        SystemBuildTraceService traces = mock(SystemBuildTraceService.class);
        ThemeIntelligenceService themeIntelligence = mock(ThemeIntelligenceService.class);
        ThemeSnapshotRepository themeSnapshotRepo = mock(ThemeSnapshotRepository.class);

        when(replay.build(date)).thenReturn(new ThemeReplayTimelineResponse(date, null, null, null,
                List.of(), List.of(), List.of(),
                null, null, null, List.of(), List.of(), ThemeReplayMetricsResponse.MetricsSummary.empty(),
                ThemeReplayTimelineResponse.SafetyBoundary.replayOnlyBoundary(), true, true));
        when(research.build(date)).thenReturn(new ResearchUniverseResponse(date, true, true,
                ResearchUniverseResponse.SafetyBoundary.researchOnlyBoundary(), ThemeReplayMetricsResponse.MetricsSummary.empty(), List.of()));
        when(lifecycle.build(date)).thenReturn(new ThemeLifecycleResponse.BuildResult(date, 1, true, true,
                ThemeLifecycleResponse.SafetyBoundary.lifecycleReplayOnlyBoundary(), Map.of("PASSIVE", "MAINSTREAM"), List.of()));
        when(metrics.build(date)).thenReturn(new ThemeReplayMetricsResponse.BuildResult(date, 1, true, true, true,
                ThemeReplayMetricsResponse.SafetyBoundary.replayAnalyticsOnlyBoundary(), Map.of("PASSIVE", ThemeReplayMetricsResponse.MetricsSummary.empty()), List.of()));
        when(hot.safetyBoundary()).thenReturn(HotGroupRadarResponse.SafetyBoundary.shadowOnlyBoundary());
        when(hot.buildFromDefaultFile(date, "POSTMARKET")).thenReturn(new HotGroupRadarResponse(date, "POSTMARKET", true, true, true, true, true,
                HotGroupRadarResponse.SafetyBoundary.shadowOnlyBoundary(), List.of(), List.of()));
        when(promotion.safetyBoundary()).thenReturn(PromotionReviewResponse.defaultSafetyBoundary());
        when(promotion.rebuild(date)).thenReturn(PromotionReviewResponse.of(date, List.of(), 0, 0, 0));
        when(themeSnapshotRepo.countByTradingDate(date)).thenReturn(2L);
        when(themeIntelligence.themeSnapshotFreshness()).thenReturn(new com.austin.trading.dto.response.DataFreshnessSnapshot(
                date, 0, com.austin.trading.domain.enums.DataFreshnessStatus.LIVE, false, null));

        SystemBuildTraceEntity traceEntity = new SystemBuildTraceEntity();
        when(traces.start(anyString(), eq(date), any(), any())).thenReturn(traceEntity);

        ThemeReplaySnapshotRepository snapshots = mock(ThemeReplaySnapshotRepository.class);
        ThemeReplayNodeRepository nodes = mock(ThemeReplayNodeRepository.class);
        ThemeReplayEdgeRepository edges = mock(ThemeReplayEdgeRepository.class);
        ResearchUniverseItemRepository researchRepo = mock(ResearchUniverseItemRepository.class);
        ThemeLifecycleStateRepository lifecycleRepo = mock(ThemeLifecycleStateRepository.class);
        ThemeReplayMetricsRepository metricsRepo = mock(ThemeReplayMetricsRepository.class);
        HotGroupRadarSnapshotRepository hotSnapshotRepo = mock(HotGroupRadarSnapshotRepository.class);
        HotGroupStockSignalRepository hotSignalRepo = mock(HotGroupStockSignalRepository.class);
        CandidateThemeRadarTraceRepository radarTraceRepo = mock(CandidateThemeRadarTraceRepository.class);
        PromotionReviewItemRepository promotionItemRepo = mock(PromotionReviewItemRepository.class);
        PromotionReviewAuditRepository promotionAuditRepo = mock(PromotionReviewAuditRepository.class);

        when(snapshots.countByTradingDate(date)).thenReturn(0L, 1L, 1L);
        when(nodes.countByTradingDate(date)).thenReturn(0L, 1L, 1L);
        when(edges.countByTradingDate(date)).thenReturn(0L, 1L, 1L);
        when(researchRepo.countByTradingDate(date)).thenReturn(0L);
        when(lifecycleRepo.countByTradingDate(date)).thenReturn(0L);
        when(metricsRepo.countByTradingDate(date)).thenReturn(0L);
        when(hotSnapshotRepo.countByTradingDateAndSourcePhase(date, "POSTMARKET")).thenReturn(0L);
        when(hotSignalRepo.countByTradingDateAndSourcePhase(date, "POSTMARKET")).thenReturn(0L);
        when(promotionItemRepo.countByTradingDate(date)).thenReturn(0L);
        when(promotionAuditRepo.countByTradingDate(date)).thenReturn(0L);

        BuildOperationsService service = new BuildOperationsService(
                replay, research, lifecycle, metrics, hot, promotion, traces,
                themeIntelligence, themeSnapshotRepo,
                snapshots, nodes, edges, researchRepo, lifecycleRepo, metricsRepo,
                hotSnapshotRepo, hotSignalRepo, radarTraceRepo, promotionItemRepo, promotionAuditRepo);

        var result = service.buildDailyThemeOps(date);

        assertThat(result).containsKeys("themeReplay", "themeLifecycle", "replayMetrics", "hotGroupRadar", "researchUniverse", "promotionReview", "themeIntelligenceSnapshot");
        verify(traces, atLeast(7)).start(anyString(), eq(date), any(), any());
        verify(traces).start(eq("THEME_REPLAY"), eq(date), isNull(), any());
        verify(traces).start(eq("LIFECYCLE"), eq(date), isNull(), any());
        verify(traces).start(eq("REPLAY_METRICS"), eq(date), isNull(), any());
        verify(traces).start(eq("HOT_GROUP_RADAR"), eq(date), eq("POSTMARKET"), any());
        verify(traces).start(eq("RESEARCH_UNIVERSE"), eq(date), isNull(), any());
        verify(traces).start(eq("PROMOTION_REVIEW"), eq(date), isNull(), any());
        verify(traces).start(eq("THEME_INTELLIGENCE_SNAPSHOT"), eq(date), isNull(), any());
    }
}
