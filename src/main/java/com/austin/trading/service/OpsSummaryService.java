package com.austin.trading.service;

import com.austin.trading.dto.response.ThemeReplayMetricsResponse;
import com.austin.trading.entity.SystemBuildTraceEntity;
import com.austin.trading.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final HotGroupStockSignalRepository hotSignalRepo;
    private final CandidateThemeRadarTraceRepository radarTraceRepo;
    private final PromotionReviewItemRepository promotionRepo;
    private final ThemeReplayMetricsRepository metricsRepo;
    private final ThemeSnapshotRepository themeSnapshotRepo;
    private final KolThemeSignalRepository kolSignalRepo;
    private final KolThemeSignalDailySnapshotRepository kolSnapshotRepo;
    private final ReplayMetricsService replayMetricsService;
    private final SystemBuildTraceRepository traceRepo;
    private final DataFreshnessService freshnessService;

    @Autowired
    public OpsSummaryService(ThemeReplaySnapshotRepository replaySnapshotRepo,
                             ThemeLifecycleStateRepository lifecycleRepo,
                             ResearchUniverseItemRepository researchRepo,
                             HotGroupRadarSnapshotRepository hotGroupRepo,
                             PromotionReviewItemRepository promotionRepo,
                             ThemeReplayMetricsRepository metricsRepo,
                             ReplayMetricsService replayMetricsService,
                             SystemBuildTraceRepository traceRepo) {
        this(replaySnapshotRepo, lifecycleRepo, researchRepo, hotGroupRepo, null, null,
                promotionRepo, metricsRepo, null, null, null, replayMetricsService, traceRepo, new DataFreshnessService());
    }

    public OpsSummaryService(ThemeReplaySnapshotRepository replaySnapshotRepo,
                             ThemeLifecycleStateRepository lifecycleRepo,
                             ResearchUniverseItemRepository researchRepo,
                             HotGroupRadarSnapshotRepository hotGroupRepo,
                             HotGroupStockSignalRepository hotSignalRepo,
                             CandidateThemeRadarTraceRepository radarTraceRepo,
                             PromotionReviewItemRepository promotionRepo,
                             ThemeReplayMetricsRepository metricsRepo,
                             ThemeSnapshotRepository themeSnapshotRepo,
                             KolThemeSignalRepository kolSignalRepo,
                             KolThemeSignalDailySnapshotRepository kolSnapshotRepo,
                             ReplayMetricsService replayMetricsService,
                             SystemBuildTraceRepository traceRepo,
                             DataFreshnessService freshnessService) {
        this.replaySnapshotRepo = replaySnapshotRepo;
        this.lifecycleRepo = lifecycleRepo;
        this.researchRepo = researchRepo;
        this.hotGroupRepo = hotGroupRepo;
        this.hotSignalRepo = hotSignalRepo;
        this.radarTraceRepo = radarTraceRepo;
        this.promotionRepo = promotionRepo;
        this.metricsRepo = metricsRepo;
        this.themeSnapshotRepo = themeSnapshotRepo;
        this.kolSignalRepo = kolSignalRepo;
        this.kolSnapshotRepo = kolSnapshotRepo;
        this.replayMetricsService = replayMetricsService;
        this.traceRepo = traceRepo;
        this.freshnessService = freshnessService;
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
        out.put("themeSnapshotCount", themeSnapshotRepo == null ? 0 : themeSnapshotRepo.countByTradingDate(date));
        out.put("candidateThemeRadarTraceCount", radarTraceRepo == null ? 0 : radarTraceRepo.countByTradingDate(date));
        out.put("safetyViolationsCount", safety.safetyViolationDetected() ? 1 : 0);
        out.put("forbiddenCountersSummary", forbidden);
        out.put("buildStatusSummary", buildStatusSummary);
        out.put("freshness", themeFreshness(date));
        out.put("safetyBoundary", safety.safetyBoundary());
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> buildTraces(LocalDate date) {
        List<Map<String, Object>> traces = traceRepo.findByTradingDateOrderByStartedAtDescIdDesc(date).stream().map(this::traceItem).toList();
        return Map.of("tradingDate", date, "traces", traces);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> themeFreshness(LocalDate date) {
        LocalDate target = date == null ? freshnessService.today() : date;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tradingDate", target);
        out.put("layers", List.of(
                layer("ThemeReplay", latestReplayDate(), replaySnapshotRepo.countByTradingDate(target), latestTrace("THEME_REPLAY", target)),
                layer("ThemeLifecycle", latestLifecycleDate(), lifecycleRepo.countByTradingDate(target), latestTrace("LIFECYCLE", target)),
                layer("ReplayMetrics", latestMetricsDate(), metricsRepo.countByTradingDate(target), latestTrace("REPLAY_METRICS", target)),
                layer("HotGroupRadar", latestHotGroupDate(), hotGroupRepo.countByTradingDateAndSourcePhase(target, "POSTMARKET") + (hotSignalRepo == null ? 0 : hotSignalRepo.countByTradingDateAndSourcePhase(target, "POSTMARKET")), latestTrace("HOT_GROUP_RADAR", target)),
                layer("ResearchUniverse", latestResearchDate(), researchRepo.countByTradingDate(target), latestTrace("RESEARCH_UNIVERSE", target)),
                layer("CandidateThemeRadarTrace", latestRadarTraceDate(), radarTraceRepo == null ? 0 : radarTraceRepo.countByTradingDate(target), null),
                layer("ThemeIntelligenceSnapshot", themeSnapshotRepo == null ? null : themeSnapshotRepo.findLatestValidTradingDate(freshnessService.today()), themeSnapshotRepo == null ? 0 : themeSnapshotRepo.countByTradingDate(target), latestTrace("THEME_INTELLIGENCE_SNAPSHOT", target)),
                layer("KOLManualSignals", kolSignalRepo == null ? null : kolSignalRepo.findLatestTradingDate(), kolSignalRepo == null ? 0 : kolSignalRepo.countByTradingDate(target), null),
                layer("NarrativeDailySnapshot", kolSnapshotRepo == null ? null : kolSnapshotRepo.findLatestTradingDate(), kolSnapshotRepo == null ? 0 : kolSnapshotRepo.countByTradingDate(target), null)
        ));
        out.put("futureDataDetected", themeSnapshotRepo != null && themeSnapshotRepo.countFutureRows(freshnessService.today()) > 0);
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> buildStatus(LocalDate date) {
        LocalDate target = date == null ? freshnessService.today() : date;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tradingDate", target);
        out.put("builds", traceRepo.findByTradingDateOrderByStartedAtDescIdDesc(target).stream().map(this::traceItem).toList());
        out.put("freshness", themeFreshness(target));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> staleDataReport(LocalDate date) {
        Map<String, Object> freshness = themeFreshness(date);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> layers = (List<Map<String, Object>>) freshness.get("layers");
        List<Map<String, Object>> stale = layers.stream()
                .filter(l -> !"LIVE".equals(String.valueOf(l.get("dataFreshnessStatus"))))
                .toList();
        return Map.of("tradingDate", freshness.get("tradingDate"), "staleLayers", stale, "staleLayerCount", stale.size());
    }

    private Map<String, Object> layer(String name, LocalDate latestDate, long rowCountToday, SystemBuildTraceEntity latestTrace) {
        var f = freshnessService.evaluate(latestDate, false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("layer", name);
        out.put("latestDataDate", f.latestDataDate());
        out.put("staleDays", f.staleDays());
        out.put("dataFreshnessStatus", rowCountToday == 0 && f.latestDataDate() == null ? "EMPTY" : f.dataFreshnessStatus().name());
        out.put("rowCount", rowCountToday);
        out.put("dataFreshness", f.dataFreshnessStatus().name());
        if (latestTrace != null) {
            out.put("latestBuildTraceId", latestTrace.getId());
            out.put("latestBuildStatus", latestTrace.getStatus());
            out.put("latestBuildStartedAt", latestTrace.getStartedAt());
            out.put("latestBuildFinishedAt", latestTrace.getFinishedAt());
            out.put("latestBuildDurationMs", latestTrace.getDurationMs());
            out.put("latestBuildInsertedCount", latestTrace.getInsertedCount());
            out.put("latestBuildDeletedCount", latestTrace.getDeletedCount());
        }
        return out;
    }

    private LocalDate latestReplayDate() { return replaySnapshotRepo.findAll().stream().map(x -> x.getTradingDate()).max(LocalDate::compareTo).orElse(null); }
    private LocalDate latestLifecycleDate() { return lifecycleRepo.findAll().stream().map(x -> x.getTradingDate()).max(LocalDate::compareTo).orElse(null); }
    private LocalDate latestMetricsDate() { return metricsRepo.findAll().stream().map(x -> x.getTradingDate()).max(LocalDate::compareTo).orElse(null); }
    private LocalDate latestHotGroupDate() { return hotGroupRepo.findAll().stream().map(x -> x.getTradingDate()).max(LocalDate::compareTo).orElse(null); }
    private LocalDate latestResearchDate() { return researchRepo.findAll().stream().map(x -> x.getTradingDate()).max(LocalDate::compareTo).orElse(null); }
    private LocalDate latestRadarTraceDate() { return radarTraceRepo == null ? null : radarTraceRepo.findAll().stream().map(x -> x.getTradingDate()).max(LocalDate::compareTo).orElse(null); }
    private SystemBuildTraceEntity latestTrace(String buildType, LocalDate target) {
        return traceRepo.findByTradingDateAndBuildTypeOrderByStartedAtDescIdDesc(target, buildType).stream().findFirst().orElse(null);
    }

    private Map<String, Object> traceItem(SystemBuildTraceEntity t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.getId());
        out.put("buildName", t.getBuildType());
        out.put("buildType", t.getBuildType());
        out.put("tradingDate", t.getTradingDate());
        out.put("sourcePhase", t.getSourcePhase());
        out.put("startTime", t.getStartedAt());
        out.put("startedAt", t.getStartedAt());
        out.put("endTime", t.getFinishedAt());
        out.put("finishedAt", t.getFinishedAt());
        out.put("duration", t.getDurationMs());
        out.put("durationMs", t.getDurationMs());
        out.put("status", t.getStatus());
        out.put("rowCount", t.getInsertedCount());
        out.put("deletedCount", t.getDeletedCount());
        out.put("insertedCount", t.getInsertedCount());
        out.put("updatedCount", t.getUpdatedCount());
        out.put("skippedCount", t.getSkippedCount());
        out.put("errorMessage", t.getErrorMessage());
        out.put("dataFreshness", t.getPayloadJson() == null ? null : t.getPayloadJson());
        out.put("safetyBoundaryJson", t.getSafetyBoundaryJson());
        out.put("payloadJson", t.getPayloadJson());
        return out;
    }
}
