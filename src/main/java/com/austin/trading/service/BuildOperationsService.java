package com.austin.trading.service;

import com.austin.trading.dto.response.*;
import com.austin.trading.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class BuildOperationsService {
    private final ThemeReplayTimelineService themeReplay;
    private final ResearchUniverseService researchUniverse;
    private final ThemeLifecycleEngine lifecycle;
    private final ReplayMetricsService metrics;
    private final HotGroupRadarService hotGroup;
    private final PromotionReviewService promotionReview;
    private final SystemBuildTraceService traceService;
    private final ThemeIntelligenceService themeIntelligenceService;
    private final ThemeSnapshotRepository themeSnapshotRepo;
    private final ThemeReplaySnapshotRepository snapshotRepo;
    private final ThemeReplayNodeRepository nodeRepo;
    private final ThemeReplayEdgeRepository edgeRepo;
    private final ResearchUniverseItemRepository researchRepo;
    private final ThemeLifecycleStateRepository lifecycleRepo;
    private final ThemeReplayMetricsRepository metricsRepo;
    private final HotGroupRadarSnapshotRepository hotSnapshotRepo;
    private final HotGroupStockSignalRepository hotSignalRepo;
    private final CandidateThemeRadarTraceRepository radarTraceRepo;
    private final PromotionReviewItemRepository promotionItemRepo;
    private final PromotionReviewAuditRepository promotionAuditRepo;

    public BuildOperationsService(ThemeReplayTimelineService themeReplay,
                                  ResearchUniverseService researchUniverse,
                                  ThemeLifecycleEngine lifecycle,
                                  ReplayMetricsService metrics,
                                  HotGroupRadarService hotGroup,
                                  PromotionReviewService promotionReview,
                                  SystemBuildTraceService traceService,
                                  ThemeReplaySnapshotRepository snapshotRepo,
                                  ThemeReplayNodeRepository nodeRepo,
                                  ThemeReplayEdgeRepository edgeRepo,
                                  ResearchUniverseItemRepository researchRepo,
                                  ThemeLifecycleStateRepository lifecycleRepo,
                                  ThemeReplayMetricsRepository metricsRepo,
                                  HotGroupRadarSnapshotRepository hotSnapshotRepo,
                                  HotGroupStockSignalRepository hotSignalRepo,
                                  CandidateThemeRadarTraceRepository radarTraceRepo,
                                  PromotionReviewItemRepository promotionItemRepo,
                                  PromotionReviewAuditRepository promotionAuditRepo) {
        this(themeReplay, researchUniverse, lifecycle, metrics, hotGroup, promotionReview, traceService,
                null, null, snapshotRepo, nodeRepo, edgeRepo, researchRepo, lifecycleRepo, metricsRepo,
                hotSnapshotRepo, hotSignalRepo, radarTraceRepo, promotionItemRepo, promotionAuditRepo);
    }

    @Autowired
    public BuildOperationsService(ThemeReplayTimelineService themeReplay,
                                  ResearchUniverseService researchUniverse,
                                  ThemeLifecycleEngine lifecycle,
                                  ReplayMetricsService metrics,
                                  HotGroupRadarService hotGroup,
                                  PromotionReviewService promotionReview,
                                  SystemBuildTraceService traceService,
                                  ThemeIntelligenceService themeIntelligenceService,
                                  ThemeSnapshotRepository themeSnapshotRepo,
                                  ThemeReplaySnapshotRepository snapshotRepo,
                                  ThemeReplayNodeRepository nodeRepo,
                                  ThemeReplayEdgeRepository edgeRepo,
                                  ResearchUniverseItemRepository researchRepo,
                                  ThemeLifecycleStateRepository lifecycleRepo,
                                  ThemeReplayMetricsRepository metricsRepo,
                                  HotGroupRadarSnapshotRepository hotSnapshotRepo,
                                  HotGroupStockSignalRepository hotSignalRepo,
                                  CandidateThemeRadarTraceRepository radarTraceRepo,
                                  PromotionReviewItemRepository promotionItemRepo,
                                  PromotionReviewAuditRepository promotionAuditRepo) {
        this.themeReplay = themeReplay;
        this.researchUniverse = researchUniverse;
        this.lifecycle = lifecycle;
        this.metrics = metrics;
        this.hotGroup = hotGroup;
        this.promotionReview = promotionReview;
        this.traceService = traceService;
        this.themeIntelligenceService = themeIntelligenceService;
        this.themeSnapshotRepo = themeSnapshotRepo;
        this.snapshotRepo = snapshotRepo;
        this.nodeRepo = nodeRepo;
        this.edgeRepo = edgeRepo;
        this.researchRepo = researchRepo;
        this.lifecycleRepo = lifecycleRepo;
        this.metricsRepo = metricsRepo;
        this.hotSnapshotRepo = hotSnapshotRepo;
        this.hotSignalRepo = hotSignalRepo;
        this.radarTraceRepo = radarTraceRepo;
        this.promotionItemRepo = promotionItemRepo;
        this.promotionAuditRepo = promotionAuditRepo;
    }

    public BuildOperationResponse buildThemeReplay(LocalDate date) {
        int deleted = safe(snapshotRepo.countByTradingDate(date)) + safe(nodeRepo.countByTradingDate(date)) + safe(edgeRepo.countByTradingDate(date));
        Object safety = ThemeReplayTimelineResponse.SafetyBoundary.replayOnlyBoundary();
        Long traceId = traceService.start("THEME_REPLAY", date, null, safety).getId();
        try {
            themeReplay.build(date);
            int built = safe(snapshotRepo.countByTradingDate(date)) + safe(nodeRepo.countByTradingDate(date)) + safe(edgeRepo.countByTradingDate(date));
            return success("THEME_REPLAY", date, null, deleted, built, safety, traceId, Map.of("snapshots", snapshotRepo.countByTradingDate(date), "nodes", nodeRepo.countByTradingDate(date), "edges", edgeRepo.countByTradingDate(date)));
        } catch (RuntimeException e) {
            traceService.failed(traceId, deleted, 0, 0, e, Map.of("stage", "themeReplay.build"));
            throw e;
        }
    }

    public BuildOperationResponse buildResearchUniverse(LocalDate date) {
        int deleted = safe(researchRepo.countByTradingDate(date));
        Object safety = ResearchUniverseResponse.SafetyBoundary.researchOnlyBoundary();
        Long traceId = traceService.start("RESEARCH_UNIVERSE", date, null, safety).getId();
        try {
            ResearchUniverseResponse response = researchUniverse.build(date);
            int built = response.items().size();
            return success("RESEARCH_UNIVERSE", date, null, deleted, built, safety, traceId, Map.of("items", built));
        } catch (RuntimeException e) {
            traceService.failed(traceId, deleted, 0, 0, e, Map.of("stage", "researchUniverse.build"));
            throw e;
        }
    }

    public BuildOperationResponse buildLifecycle(LocalDate date) {
        int deleted = safe(lifecycleRepo.countByTradingDate(date));
        Object safety = ThemeLifecycleResponse.SafetyBoundary.lifecycleReplayOnlyBoundary();
        Long traceId = traceService.start("LIFECYCLE", date, null, safety).getId();
        try {
            ThemeLifecycleResponse.BuildResult result = lifecycle.build(date);
            return success("LIFECYCLE", date, null, deleted, result.builtCount(), safety, traceId, Map.of("stages", result.stages()));
        } catch (RuntimeException e) {
            traceService.failed(traceId, deleted, 0, 0, e, Map.of("stage", "lifecycle.build"));
            throw e;
        }
    }

    public BuildOperationResponse buildReplayMetrics(LocalDate date) {
        int deleted = safe(metricsRepo.countByTradingDate(date));
        Object safety = ThemeReplayMetricsResponse.SafetyBoundary.replayAnalyticsOnlyBoundary();
        Long traceId = traceService.start("REPLAY_METRICS", date, null, safety).getId();
        try {
            ThemeReplayMetricsResponse.BuildResult result = metrics.build(date);
            return success("REPLAY_METRICS", date, null, deleted, result.builtCount(), safety, traceId, new LinkedHashMap<>(Map.of("metrics", result.metrics())));
        } catch (RuntimeException e) {
            traceService.failed(traceId, deleted, 0, 0, e, Map.of("stage", "metrics.build"));
            throw e;
        }
    }

    public BuildOperationResponse buildHotGroups(LocalDate date, String phase) {
        String sourcePhase = phase == null || phase.isBlank() ? "POSTMARKET" : phase.trim().toUpperCase();
        int deleted = safe(hotSnapshotRepo.countByTradingDateAndSourcePhase(date, sourcePhase))
                + safe(hotSignalRepo.countByTradingDateAndSourcePhase(date, sourcePhase))
                + safe(radarTraceRepo.countByTradingDate(date));
        Object safety = hotGroup.safetyBoundary();
        Long traceId = traceService.start("HOT_GROUP_RADAR", date, sourcePhase, safety).getId();
        try {
            HotGroupRadarResponse response = hotGroup.buildFromDefaultFile(date, sourcePhase);
            int built = response.themes().size() + response.signals().size();
            return success("HOT_GROUP_RADAR", date, sourcePhase, deleted, built, safety, traceId, Map.of("themes", response.themes().size(), "signals", response.signals().size()));
        } catch (RuntimeException e) {
            traceService.failed(traceId, deleted, 0, 0, e, Map.of("stage", "hotGroup.build"));
            throw e;
        }
    }

    public BuildOperationResponse buildThemeIntelligenceSnapshot(LocalDate date) {
        Object safety = Map.of(
                "observabilityOnly", true,
                "productionDecisionAllowed", false,
                "manualConfirmRequired", true,
                "doesNotAffectBuySellEnter", true);
        Long traceId = traceService.start("THEME_INTELLIGENCE_SNAPSHOT", date, null, safety).getId();
        try {
            int rowCount = safe(themeSnapshotRepo.countByTradingDate(date));
            var freshness = themeIntelligenceService.themeSnapshotFreshness();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("rowCount", rowCount);
            payload.put("dataFreshness", freshness.dataFreshnessStatus().name());
            payload.put("latestDataDate", freshness.latestDataDate());
            payload.put("staleDays", freshness.staleDays());
            payload.put("futureDataDetected", freshness.futureDataDetected());
            payload.put("warning", freshness.warning());
            traceService.success(traceId, 0, rowCount, 0, 0, payload);
            return BuildOperationResponse.builder("THEME_INTELLIGENCE_SNAPSHOT", date)
                    .builtCount(rowCount)
                    .safetyBoundary(safety)
                    .traceId(traceId)
                    .status("SUCCESS")
                    .payload(payload)
                    .build();
        } catch (RuntimeException e) {
            traceService.failed(traceId, 0, 0, 0, e, Map.of("stage", "themeIntelligenceSnapshot.freshness"));
            throw e;
        }
    }

    public Map<String, Object> buildDailyThemeOps(LocalDate date) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tradingDate", date);
        out.put("themeReplay", buildThemeReplay(date));
        out.put("themeLifecycle", buildLifecycle(date));
        out.put("replayMetrics", buildReplayMetrics(date));
        out.put("hotGroupRadar", buildHotGroups(date, "POSTMARKET"));
        out.put("researchUniverse", buildResearchUniverse(date));
        out.put("promotionReview", buildPromotionReview(date));
        out.put("themeIntelligenceSnapshot", buildThemeIntelligenceSnapshot(date));
        return out;
    }

    public BuildOperationResponse buildPromotionReview(LocalDate date) {
        int deleted = safe(promotionItemRepo.countByTradingDate(date)) + safe(promotionAuditRepo.countByTradingDate(date));
        Object safety = promotionReview.safetyBoundary();
        Long traceId = traceService.start("PROMOTION_REVIEW", date, null, safety).getId();
        try {
            PromotionReviewResponse response = promotionReview.rebuild(date);
            int built = response.items().size();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("items", built);
            payload.put("manualReviewPreserved", response.manualReviewPreserved());
            payload.put("preservedManualCount", response.preservedManualCount());
            payload.put("mergedManualCount", response.mergedManualCount());
            payload.put("deletedSystemCount", response.deletedSystemCount());
            return success("PROMOTION_REVIEW", date, null, deleted, built, safety, traceId, payload)
                    .toBuilder()
                    .preservedManualCount(response.preservedManualCount())
                    .mergedManualCount(response.mergedManualCount())
                    .deletedSystemCount(response.deletedSystemCount())
                    .manualReviewPreserved(response.manualReviewPreserved())
                    .build();
        } catch (RuntimeException e) {
            traceService.failed(traceId, deleted, 0, 0, e, Map.of("stage", "promotionReview.build"));
            throw e;
        }
    }

    private BuildOperationResponse success(String type, LocalDate date, String phase, int deleted, int built, Object safety, Long traceId, Map<String, Object> payload) {
        traceService.success(traceId, deleted, built, 0, 0, payload);
        return BuildOperationResponse.builder(type, date)
                .sourcePhase(phase)
                .deletedCount(deleted)
                .builtCount(built)
                .insertedCount(built)
                .skippedCount(0)
                .rebuild(true)
                .safetyBoundary(safety)
                .traceId(traceId)
                .status("SUCCESS")
                .payload(payload)
                .build();
    }

    private int safe(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
