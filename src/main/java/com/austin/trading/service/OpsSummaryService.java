package com.austin.trading.service;

import com.austin.trading.dto.response.ThemeReplayMetricsResponse;
import com.austin.trading.entity.SystemBuildTraceEntity;
import com.austin.trading.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OpsSummaryService {
    private final ThemeReplaySnapshotRepository replaySnapshotRepo;
    private final ThemeLifecycleStateRepository lifecycleRepo;
    private final ResearchUniverseItemRepository researchRepo;
    private final HotGroupRadarSnapshotRepository hotGroupRepo;
    private final PromotionReviewItemRepository promotionRepo;
    private final ThemeReplayMetricsRepository metricsRepo;
    private final ReplayMetricsService replayMetricsService;
    private final SystemBuildTraceRepository traceRepo;

    public OpsSummaryService(ThemeReplaySnapshotRepository replaySnapshotRepo,
                             ThemeLifecycleStateRepository lifecycleRepo,
                             ResearchUniverseItemRepository researchRepo,
                             HotGroupRadarSnapshotRepository hotGroupRepo,
                             PromotionReviewItemRepository promotionRepo,
                             ThemeReplayMetricsRepository metricsRepo,
                             ReplayMetricsService replayMetricsService,
                             SystemBuildTraceRepository traceRepo) {
        this.replaySnapshotRepo = replaySnapshotRepo;
        this.lifecycleRepo = lifecycleRepo;
        this.researchRepo = researchRepo;
        this.hotGroupRepo = hotGroupRepo;
        this.promotionRepo = promotionRepo;
        this.metricsRepo = metricsRepo;
        this.replayMetricsService = replayMetricsService;
        this.traceRepo = traceRepo;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> dailySummary(LocalDate date) {
        ThemeReplayMetricsResponse.SafetySummary safety = replayMetricsService.safetySummary(date);
        List<SystemBuildTraceEntity> traces = traceRepo.findByTradingDateOrderByStartedAtDescIdDesc(date);
        Map<String, Long> buildStatusSummary = traces.stream().collect(Collectors.groupingBy(
                t -> t.getBuildType() + ":" + t.getStatus(), LinkedHashMap::new, Collectors.counting()));
        Map<String, Object> forbidden = new LinkedHashMap<>();
        forbidden.put("riskGateBypassCount", safety.riskGateBypassCount());
        forbidden.put("leadershipOnlyEnteredCount", safety.leadershipOnlyEnteredCount());
        forbidden.put("leaderTradableFalseEnterCount", safety.leaderTradableFalseEnterCount());
        forbidden.put("peerShadowDirectPromotionCount", safety.peerShadowDirectPromotionCount());
        forbidden.put("narrativeDirectEnterCount", safety.narrativeDirectEnterCount());
        forbidden.put("researchVsTradableSeparationViolationCount", safety.researchVsTradableSeparationViolationCount());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tradingDate", date);
        out.put("replayThemesCount", replaySnapshotRepo.countByTradingDate(date));
        out.put("lifecycleCount", lifecycleRepo.countByTradingDate(date));
        out.put("researchUniverseCount", researchRepo.countByTradingDate(date));
        out.put("hotGroupCount", hotGroupRepo.findByTradingDateAndSourcePhaseOrderByHotScoreDesc(date, "POSTMARKET").size());
        out.put("promotionReviewCount", promotionRepo.countByTradingDate(date));
        out.put("metricsCount", metricsRepo.countByTradingDate(date));
        out.put("safetyViolationsCount", safety.safetyViolationDetected() ? 1 : 0);
        out.put("forbiddenCountersSummary", forbidden);
        out.put("buildStatusSummary", buildStatusSummary);
        out.put("safetyBoundary", safety.safetyBoundary());
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> buildTraces(LocalDate date) {
        List<Map<String, Object>> traces = traceRepo.findByTradingDateOrderByStartedAtDescIdDesc(date).stream().map(this::traceItem).toList();
        return Map.of("tradingDate", date, "traces", traces);
    }

    private Map<String, Object> traceItem(SystemBuildTraceEntity t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.getId());
        out.put("buildType", t.getBuildType());
        out.put("tradingDate", t.getTradingDate());
        out.put("sourcePhase", t.getSourcePhase());
        out.put("startedAt", t.getStartedAt());
        out.put("finishedAt", t.getFinishedAt());
        out.put("durationMs", t.getDurationMs());
        out.put("status", t.getStatus());
        out.put("deletedCount", t.getDeletedCount());
        out.put("insertedCount", t.getInsertedCount());
        out.put("updatedCount", t.getUpdatedCount());
        out.put("skippedCount", t.getSkippedCount());
        out.put("errorMessage", t.getErrorMessage());
        out.put("safetyBoundaryJson", t.getSafetyBoundaryJson());
        out.put("payloadJson", t.getPayloadJson());
        return out;
    }
}
