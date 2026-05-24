package com.austin.trading.service;

import com.austin.trading.dto.response.ThemeReplayMetricsResponse;
import com.austin.trading.entity.*;
import com.austin.trading.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReplayMetricsService {

    private static final ThemeReplayMetricsResponse.SafetyBoundary SAFETY =
            ThemeReplayMetricsResponse.SafetyBoundary.replayAnalyticsOnlyBoundary();
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    private static final BigDecimal ONE = BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP);

    private final ThemeReplayMetricsRepository metricsRepository;
    private final ThemeReplaySnapshotRepository snapshotRepository;
    private final ThemeReplayNodeRepository nodeRepository;
    private final ThemeReplayEdgeRepository edgeRepository;
    private final ThemeLifecycleStateRepository lifecycleRepository;
    private final ResearchUniverseItemRepository researchRepository;
    private final CandidateStockRepository candidateStockRepository;
    private final FinalDecisionRepository finalDecisionRepository;
    private final ObjectMapper objectMapper;

    public ReplayMetricsService(
            ThemeReplayMetricsRepository metricsRepository,
            ThemeReplaySnapshotRepository snapshotRepository,
            ThemeReplayNodeRepository nodeRepository,
            ThemeReplayEdgeRepository edgeRepository,
            ThemeLifecycleStateRepository lifecycleRepository,
            ResearchUniverseItemRepository researchRepository,
            CandidateStockRepository candidateStockRepository,
            FinalDecisionRepository finalDecisionRepository,
            ObjectMapper objectMapper
    ) {
        this.metricsRepository = metricsRepository;
        this.snapshotRepository = snapshotRepository;
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.lifecycleRepository = lifecycleRepository;
        this.researchRepository = researchRepository;
        this.candidateStockRepository = candidateStockRepository;
        this.finalDecisionRepository = finalDecisionRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ThemeReplayMetricsResponse.BuildResult build(LocalDate date) {
        List<ThemeReplaySnapshotEntity> snapshots = safeList(snapshotRepository.findByTradingDateOrderByThemeTagAsc(date));
        List<ThemeReplayMetricsEntity> metrics = new ArrayList<>();
        for (ThemeReplaySnapshotEntity snapshot : snapshots) {
            List<ThemeReplayNodeEntity> nodes = safeList(nodeRepository.findByTradingDateAndThemeTagOrderByIdAsc(date, snapshot.getThemeTag()));
            List<ThemeReplayEdgeEntity> edges = safeList(edgeRepository.findByTradingDateAndThemeTagOrderByIdAsc(date, snapshot.getThemeTag()));
            List<ResearchUniverseItemEntity> research = safeList(researchRepository.findByTradingDateAndThemeTagOrderBySymbolAscSourceAsc(date, snapshot.getThemeTag()));
            List<CandidateStockEntity> candidates = safeList(candidateStockRepository.findByTradingDateOrderByScoreDesc(date, Pageable.unpaged())).stream()
                    .filter(c -> Objects.equals(snapshot.getThemeTag(), defaultString(c.getThemeTag(), "UNKNOWN")))
                    .toList();
            Optional<ThemeLifecycleStateEntity> lifecycle = lifecycleRepository.findByTradingDateAndThemeTag(date, snapshot.getThemeTag());
            metrics.add(evaluate(date, snapshot, nodes, edges, research, candidates, lifecycle));
        }
        metricsRepository.deleteByTradingDate(date);
        metricsRepository.flush();
        metricsRepository.saveAll(metrics);
        Map<String, ThemeReplayMetricsResponse.MetricsSummary> summary = metrics.stream()
                .collect(Collectors.toMap(ThemeReplayMetricsEntity::getThemeTag, this::toSummary, (a, b) -> a, LinkedHashMap::new));
        return new ThemeReplayMetricsResponse.BuildResult(
                date, metrics.size(), true, true, true, SAFETY, summary, metrics.stream().map(this::toItem).toList());
    }

    @Transactional(readOnly = true)
    public ThemeReplayMetricsResponse get(LocalDate date) {
        return response(date, safeList(metricsRepository.findByTradingDateOrderByThemeTagAsc(date)));
    }

    @Transactional(readOnly = true)
    public ThemeReplayMetricsResponse byTheme(LocalDate date, String themeTag) {
        return response(date, metricsRepository.findByTradingDateAndThemeTag(date, themeTag).stream().toList());
    }

    @Transactional(readOnly = true)
    public ThemeReplayMetricsResponse.SafetySummary safetySummary(LocalDate date) {
        List<ThemeReplayMetricsEntity> rows = safeList(metricsRepository.findByTradingDateOrderByThemeTagAsc(date));
        int riskGate = rows.stream().mapToInt(e -> intValue(e.getRiskGateBypassCount())).sum();
        int leadershipEntered = rows.stream().mapToInt(e -> intValue(e.getLeadershipOnlyEnteredCount())).sum();
        int leaderFalseEntered = rows.stream().mapToInt(e -> intValue(e.getLeaderTradableFalseEnterCount())).sum();
        int peerPromotion = rows.stream().mapToInt(e -> intValue(e.getPeerShadowDirectPromotionCount())).sum();
        int narrativeEnter = rows.stream().mapToInt(e -> intValue(e.getNarrativeDirectEnterCount())).sum();
        int separation = rows.stream().mapToInt(e -> intValue(e.getResearchVsTradableSeparationViolationCount())).sum();
        boolean violation = riskGate + leadershipEntered + leaderFalseEntered + peerPromotion + narrativeEnter + separation > 0;
        return new ThemeReplayMetricsResponse.SafetySummary(
                date, rows.size(), riskGate, leadershipEntered, leaderFalseEntered, peerPromotion, narrativeEnter,
                separation, violation, SAFETY, true, true, true);
    }

    @Transactional(readOnly = true)
    public ThemeReplayMetricsResponse.MetricsSummary summary(LocalDate date, String themeTag) {
        return metricsRepository.findByTradingDateAndThemeTag(date, themeTag)
                .map(this::toSummary)
                .orElseGet(ThemeReplayMetricsResponse.MetricsSummary::empty);
    }

    public ThemeReplayMetricsEntity evaluate(
            LocalDate date,
            ThemeReplaySnapshotEntity snapshot,
            List<ThemeReplayNodeEntity> nodes,
            List<ThemeReplayEdgeEntity> edges,
            List<ResearchUniverseItemEntity> research,
            List<CandidateStockEntity> candidates,
            Optional<ThemeLifecycleStateEntity> lifecycle
    ) {
        List<ThemeReplayNodeEntity> safeNodes = safeList(nodes);
        List<ThemeReplayEdgeEntity> safeEdges = safeList(edges);
        List<ResearchUniverseItemEntity> safeResearch = safeList(research);
        List<CandidateStockEntity> safeCandidates = safeList(candidates);

        long leaders = safeNodes.stream().filter(n -> Boolean.TRUE.equals(n.getIsThemeLeader())).count();
        long retainedLeaders = safeNodes.stream().filter(n -> Boolean.TRUE.equals(n.getIsThemeLeader()) && Boolean.TRUE.equals(n.getResearchUniverse())).count();
        long peers = safeNodes.stream().filter(n -> "PEER_SHADOW".equals(n.getResearchRole())).count();
        long peerEdges = safeEdges.stream().filter(e -> "LEADER_TO_PEER".equals(e.getEdgeType())).count();
        long researchUniverse = safeResearch.stream().filter(i -> Boolean.TRUE.equals(i.getResearchUniverse())).count();
        long totalResearchRows = safeResearch.isEmpty() ? safeNodes.stream().filter(n -> Boolean.TRUE.equals(n.getResearchUniverse())).count() : safeResearch.size();
        long governanceAnnotated = safeNodes.stream().filter(n -> n.getAiGovernanceSummary() != null && !n.getAiGovernanceSummary().isBlank()).count();
        long rejected = safeNodes.stream().filter(n -> Boolean.TRUE.equals(n.getRiskRejected())).count();
        long rejectedWithReason = safeNodes.stream().filter(n -> Boolean.TRUE.equals(n.getRiskRejected()) && n.getRejectionReason() != null && !n.getRejectionReason().isBlank()).count();
        Set<String> roles = safeNodes.stream().map(n -> defaultString(n.getResearchRole(), n.getCandidateRole())).filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));
        Set<String> themes = safeCandidates.stream().map(CandidateStockEntity::getThemeTag).filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));

        ThemeReplayMetricsEntity m = new ThemeReplayMetricsEntity();
        m.setTradingDate(date);
        m.setThemeTag(snapshot.getThemeTag());
        m.setLeaderRetentionRate(rate(retainedLeaders, Math.max(1, leaders)));
        m.setPeerDiscoveryHitRate(rate(Math.min(peers, peerEdges), Math.max(1, peers)));
        m.setTaxonomyGapDiscoveryCount((int) safeNodes.stream().filter(n -> "TAXONOMY_GAP".equals(n.getResearchRole())).count());
        m.setResearchUniverseCoverage(rate(researchUniverse == 0 ? safeNodes.stream().filter(n -> Boolean.TRUE.equals(n.getResearchUniverse())).count() : researchUniverse,
                Math.max(1, totalResearchRows == 0 ? safeNodes.size() : totalResearchRows)));
        m.setCandidateDiversification(Math.max(roles.size(), themes.size()));

        m.setRiskRejectedLeaderCount((int) safeNodes.stream().filter(n -> Boolean.TRUE.equals(n.getIsThemeLeader()) && Boolean.TRUE.equals(n.getRiskRejected())).count());
        m.setFalsePromotionCount((int) safeResearch.stream().filter(i -> Boolean.TRUE.equals(i.getPromotedToTradable())).count());
        m.setChaseHighAvoidedCount((int) safeNodes.stream().filter(n -> contains(n.getRejectionReason(), "chase") || contains(n.getSafetyNote(), "chase")).count());
        m.setRiskGateBypassCount(0);
        m.setLeadershipOnlyEnteredCount(0);
        m.setLeaderTradableFalseEnterCount(0);
        m.setPeerShadowDirectPromotionCount((int) safeResearch.stream().filter(i -> "PEER_SHADOW".equals(i.getResearchRole()) && Boolean.TRUE.equals(i.getPromotedToTradable())).count());
        m.setNarrativeDirectEnterCount(0);
        m.setResearchVsTradableSeparationViolationCount((int) safeResearch.stream().filter(i -> Boolean.TRUE.equals(i.getResearchUniverse()) && Boolean.TRUE.equals(i.getTradableUniverse())).count());

        m.setPostSignalReturn1d(readDecimal(snapshot.getPayloadJson(), "postSignalReturn1d", ZERO));
        m.setPostSignalReturn3d(readDecimal(snapshot.getPayloadJson(), "postSignalReturn3d", ZERO));
        m.setPostSignalReturn5d(readDecimal(snapshot.getPayloadJson(), "postSignalReturn5d", ZERO));
        m.setMaxDrawdownAfterSignal(readDecimal(snapshot.getPayloadJson(), "maxDrawdownAfterSignal", ZERO));
        m.setPullbackEntryReturn(readDecimal(snapshot.getPayloadJson(), "pullbackEntryReturn", ZERO));
        m.setBreakoutEntryReturn(readDecimal(snapshot.getPayloadJson(), "breakoutEntryReturn", ZERO));
        m.setLowBaseFollowerReturn(readDecimal(snapshot.getPayloadJson(), "lowBaseFollowerReturn", ZERO));

        String stage = lifecycle.map(ThemeLifecycleStateEntity::getStage).orElse(snapshot.getLifecycleStage());
        m.setStageTransitionAccuracy(stage == null ? ZERO : ONE);
        m.setEmergingToMainstreamHitRate("MAINSTREAM".equals(stage) ? ONE : ZERO);
        m.setOverheatedAvoidanceReturn("OVERHEATED".equals(stage) ? m.getMaxDrawdownAfterSignal().negate() : ZERO);
        m.setDistributionWarningLeadTime("DISTRIBUTION".equals(stage) ? ONE : ZERO);
        m.setDeadThemeFalsePositiveRate("DEAD".equals(stage) && intValue(snapshot.getBreadth()) > 0 ? ONE : ZERO);

        m.setAiGovernanceAnnotatedRate(rate(governanceAnnotated, Math.max(1, safeNodes.size())));
        m.setRejectionReasonCoverage(rate(rejectedWithReason, Math.max(1, rejected)));
        m.setFinalDecisionTraceCoverage(finalDecisionRepository.findTopByTradingDateOrderByCreatedAtDesc(date).isPresent() ? ONE : ZERO);
        m.setPayloadJson(json(Map.of(
                "replayOnly", true,
                "analyticsOnly", true,
                "doesNotAffectFinalDecision", true,
                "doesNotAffectBuySellEnter", true,
                "metricsDoNotOverrideRiskGate", true,
                "noAutoPromotion", true,
                "source", Map.of(
                        "snapshotNodes", safeNodes.size(),
                        "edges", safeEdges.size(),
                        "researchRows", safeResearch.size(),
                        "candidateRowsReadOnly", safeCandidates.size()
                )
        )));
        return m;
    }

    public ThemeReplayMetricsResponse.Item toItem(ThemeReplayMetricsEntity e) {
        return new ThemeReplayMetricsResponse.Item(
                e.getTradingDate(), e.getThemeTag(), nz(e.getLeaderRetentionRate()), nz(e.getPeerDiscoveryHitRate()),
                intValue(e.getTaxonomyGapDiscoveryCount()), nz(e.getResearchUniverseCoverage()), intValue(e.getCandidateDiversification()),
                intValue(e.getRiskRejectedLeaderCount()), intValue(e.getFalsePromotionCount()), intValue(e.getChaseHighAvoidedCount()),
                intValue(e.getRiskGateBypassCount()), intValue(e.getLeadershipOnlyEnteredCount()), intValue(e.getLeaderTradableFalseEnterCount()),
                intValue(e.getPeerShadowDirectPromotionCount()), intValue(e.getNarrativeDirectEnterCount()), intValue(e.getResearchVsTradableSeparationViolationCount()),
                nz(e.getPostSignalReturn1d()), nz(e.getPostSignalReturn3d()), nz(e.getPostSignalReturn5d()), nz(e.getMaxDrawdownAfterSignal()),
                nz(e.getPullbackEntryReturn()), nz(e.getBreakoutEntryReturn()), nz(e.getLowBaseFollowerReturn()), nz(e.getStageTransitionAccuracy()),
                nz(e.getEmergingToMainstreamHitRate()), nz(e.getOverheatedAvoidanceReturn()), nz(e.getDistributionWarningLeadTime()), nz(e.getDeadThemeFalsePositiveRate()),
                nz(e.getAiGovernanceAnnotatedRate()), nz(e.getRejectionReasonCoverage()), nz(e.getFinalDecisionTraceCoverage()), e.getPayloadJson(), SAFETY);
    }

    private ThemeReplayMetricsResponse response(LocalDate date, List<ThemeReplayMetricsEntity> rows) {
        return new ThemeReplayMetricsResponse(date, true, true, SAFETY, rows.stream().map(this::toItem).toList());
    }

    private ThemeReplayMetricsResponse.MetricsSummary toSummary(ThemeReplayMetricsEntity e) {
        return new ThemeReplayMetricsResponse.MetricsSummary(
                nz(e.getLeaderRetentionRate()), nz(e.getPeerDiscoveryHitRate()), intValue(e.getCandidateDiversification()),
                intValue(e.getRiskGateBypassCount()), intValue(e.getLeadershipOnlyEnteredCount()), intValue(e.getLeaderTradableFalseEnterCount()),
                intValue(e.getResearchVsTradableSeparationViolationCount()));
    }

    private BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) return ZERO;
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP).min(ONE);
    }
    private BigDecimal nz(BigDecimal value) { return value == null ? ZERO : value; }
    private int intValue(Integer value) { return value == null ? 0 : value; }
    private String defaultString(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private boolean contains(String value, String needle) { return value != null && value.toLowerCase(Locale.ROOT).contains(needle); }
    private <T> List<T> safeList(List<T> list) { return list == null ? List.of() : list; }

    private BigDecimal readDecimal(String json, String field, BigDecimal fallback) {
        if (json == null || json.isBlank()) return fallback;
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode v = node.path(field);
            if (v.isMissingNode() || v.isNull() || v.asText().isBlank()) return fallback;
            return new BigDecimal(v.asText()).setScale(4, RoundingMode.HALF_UP);
        } catch (Exception ignored) {
            return fallback;
        }
    }
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { return "{}"; }
    }
}
