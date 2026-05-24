package com.austin.trading.service;

import com.austin.trading.dto.response.BuildOperationResponse;
import com.austin.trading.dto.response.ThemeReplayTimelineResponse;
import com.austin.trading.dto.response.PromotionReviewResponse;
import com.austin.trading.entity.SystemBuildTraceEntity;
import com.austin.trading.repository.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.List;

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

}
