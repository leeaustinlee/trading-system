package com.austin.trading.service;

import com.austin.trading.dto.response.ThemeReplaySummaryResponse;
import com.austin.trading.dto.response.ThemeReplayTimelineResponse;
import com.austin.trading.entity.*;
import com.austin.trading.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ThemeReplayTimelineService {

    private static final ThemeReplayTimelineResponse.SafetyBoundary SAFETY = ThemeReplayTimelineResponse.SafetyBoundary.replayOnlyBoundary();

    private final ThemeReplaySnapshotRepository snapshotRepository;
    private final ThemeReplayNodeRepository nodeRepository;
    private final ThemeReplayEdgeRepository edgeRepository;
    private final ThemeLeadershipSnapshotRepository leadershipSnapshotRepository;
    private final ThemeLeaderRetentionRepository leaderRetentionRepository;
    private final ThemePeerShadowCandidateRepository peerShadowCandidateRepository;
    private final CandidateStockRepository candidateStockRepository;
    private final ObjectMapper objectMapper;

    public ThemeReplayTimelineService(
            ThemeReplaySnapshotRepository snapshotRepository,
            ThemeReplayNodeRepository nodeRepository,
            ThemeReplayEdgeRepository edgeRepository,
            ThemeLeadershipSnapshotRepository leadershipSnapshotRepository,
            ThemeLeaderRetentionRepository leaderRetentionRepository,
            ThemePeerShadowCandidateRepository peerShadowCandidateRepository,
            CandidateStockRepository candidateStockRepository,
            ObjectMapper objectMapper
    ) {
        this.snapshotRepository = snapshotRepository;
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.leadershipSnapshotRepository = leadershipSnapshotRepository;
        this.leaderRetentionRepository = leaderRetentionRepository;
        this.peerShadowCandidateRepository = peerShadowCandidateRepository;
        this.candidateStockRepository = candidateStockRepository;
        this.objectMapper = objectMapper;
    }

    public List<LocalDate> dates() {
        return snapshotRepository.findDistinctTradingDatesDesc();
    }

    public List<ThemeReplaySummaryResponse> summaries(LocalDate date) {
        return snapshotRepository.findByTradingDateOrderByThemeTagAsc(date).stream()
                .map(this::toSummary)
                .toList();
    }

    public ThemeReplayTimelineResponse timeline(LocalDate date, String themeTag) {
        ThemeReplaySummaryResponse snapshot = snapshotRepository.findByTradingDateAndThemeTag(date, themeTag)
                .map(this::toSummary)
                .orElseGet(() -> new ThemeReplaySummaryResponse(
                        date, themeTag, "REPLAY_ONLY", null, 0, 0, 0, 0, 0, 0, 0, 0,
                        null, true, true, SAFETY));
        List<ThemeReplayTimelineResponse.Node> nodes = nodeRepository
                .findByTradingDateAndThemeTagOrderByIdAsc(date, themeTag).stream()
                .map(this::toNode)
                .toList();
        List<ThemeReplayTimelineResponse.Edge> edges = edgeRepository
                .findByTradingDateAndThemeTagOrderByIdAsc(date, themeTag).stream()
                .map(this::toEdge)
                .toList();
        return new ThemeReplayTimelineResponse(
                date,
                themeTag,
                snapshot.leaderSymbol(),
                snapshot,
                nodes,
                edges,
                events(nodes, edges),
                SAFETY,
                true,
                true
        );
    }

    @Transactional
    public ThemeReplayTimelineResponse build(LocalDate date) {
        List<CandidateStockEntity> candidates = safeList(candidateStockRepository.findByTradingDateOrderByScoreDesc(date, Pageable.unpaged()));
        List<ThemeLeadershipSnapshotEntity> leadershipSnapshots = safeList(leadershipSnapshotRepository.findByTradingDateOrderByLeaderRankAsc(date));
        List<ThemePeerShadowCandidateEntity> peerShadows = safeList(peerShadowCandidateRepository.findByTradingDateOrderBySourcePhaseAscLeaderSymbolAscShadowRankScoreDesc(date));
        // Explicitly read retained leaders as context without writing them back into production candidates.
        List<ThemeLeaderRetentionEntity> retainedLeaders = safeList(leaderRetentionRepository
                .findByTargetPhaseAndActiveTrueAndTradingDateLessThanEqualOrderByTradingDateDescLeaderRankAsc("OPENING", date));

        Map<String, LinkedHashMap<String, ThemeReplayNodeEntity>> groupedNodes = new TreeMap<>();
        for (CandidateStockEntity candidate : candidates) {
            addNode(groupedNodes, fromCandidate(candidate));
        }
        for (ThemeLeadershipSnapshotEntity leader : leadershipSnapshots) {
            addNode(groupedNodes, fromLeadershipSnapshot(leader));
        }
        for (ThemeLeaderRetentionEntity leader : retainedLeaders) {
            if (date.equals(leader.getTradingDate())) {
                addNode(groupedNodes, fromRetainedLeader(leader));
            }
        }
        for (ThemePeerShadowCandidateEntity peer : peerShadows) {
            addNode(groupedNodes, fromPeerShadow(peer));
        }

        List<ThemeReplayNodeEntity> nodes = groupedNodes.values().stream()
                .flatMap(m -> m.values().stream())
                .toList();
        List<ThemeReplayEdgeEntity> edges = buildEdges(nodes);
        List<ThemeReplaySnapshotEntity> snapshots = buildSnapshots(date, groupedNodes, edges);

        edgeRepository.deleteByTradingDate(date);
        nodeRepository.deleteByTradingDate(date);
        snapshotRepository.deleteByTradingDate(date);
        snapshotRepository.saveAll(snapshots);
        nodeRepository.saveAll(nodes);
        edgeRepository.saveAll(edges);

        String firstTheme = snapshots.stream().map(ThemeReplaySnapshotEntity::getThemeTag).findFirst().orElse(null);
        if (firstTheme == null) {
            return new ThemeReplayTimelineResponse(date, null, null, null, List.of(), List.of(), List.of(), SAFETY, true, true);
        }
        ThemeReplaySummaryResponse snapshot = toSummary(snapshots.stream()
                .filter(s -> firstTheme.equals(s.getThemeTag()))
                .findFirst().orElseThrow());
        List<ThemeReplayTimelineResponse.Node> responseNodes = nodes.stream()
                .filter(n -> firstTheme.equals(n.getThemeTag()))
                .map(this::toNode)
                .toList();
        List<ThemeReplayTimelineResponse.Edge> responseEdges = edges.stream()
                .filter(e -> firstTheme.equals(e.getThemeTag()))
                .map(this::toEdge)
                .toList();
        return new ThemeReplayTimelineResponse(
                date, firstTheme, snapshot.leaderSymbol(), snapshot,
                responseNodes, responseEdges, events(responseNodes, responseEdges), SAFETY, true, true);
    }

    private ThemeReplayNodeEntity fromCandidate(CandidateStockEntity candidate) {
        ThemeReplayNodeEntity node = baseNode(candidate.getTradingDate(), candidate.getThemeTag(), candidate.getSymbol(), candidate.getStockName());
        node.setCandidateRole(candidate.getCandidateRole());
        node.setResearchRole(researchRole(candidate.getCandidateRole(), Boolean.TRUE.equals(candidate.getIsThemeLeader())));
        node.setIsThemeLeader(Boolean.TRUE.equals(candidate.getIsThemeLeader()) || "THEME_LEADER".equals(candidate.getCandidateRole()));
        node.setLeadershipOnly(node.getIsThemeLeader() && !Boolean.TRUE.equals(candidate.getLeaderTradable()));
        node.setThemeLeaderSymbol(defaultString(candidate.getThemeLeaderSymbol(), node.getIsThemeLeader() ? candidate.getSymbol() : null));
        node.setLeaderTradable(Boolean.TRUE.equals(candidate.getLeaderTradable()));
        node.setThemeImportanceScore(candidate.getThemeImportanceScore());
        node.setTradableScore(candidate.getTradableScore());
        node.setShadowRankScore(candidate.getShadowRankScore());
        node.setRiskRejected(node.getLeadershipOnly() || containsRisk(candidate.getLeaderRetentionReason()));
        node.setRejectionReason(candidate.getLeaderRetentionReason());
        node.setSafetyNote("replay-only/read-only; does not affect FinalDecision, BUY/SELL/ENTER, candidate gates, or production scores");
        node.setAiGovernanceSummary(governanceSummary(node));
        node.setPayloadJson(candidate.getPayloadJson());
        return node;
    }

    private ThemeReplayNodeEntity fromLeadershipSnapshot(ThemeLeadershipSnapshotEntity leader) {
        ThemeReplayNodeEntity node = baseNode(leader.getTradingDate(), leader.getThemeTag(), leader.getSymbol(), leader.getStockName());
        node.setCandidateRole("THEME_LEADER");
        node.setResearchRole("THEME_LEADER");
        node.setIsThemeLeader(true);
        node.setLeadershipOnly(!Boolean.TRUE.equals(leader.getTradable()));
        node.setThemeLeaderSymbol(leader.getSymbol());
        node.setLeaderTradable(Boolean.TRUE.equals(leader.getTradable()));
        node.setThemeImportanceScore(leader.getScore());
        node.setShadowRankScore(leader.getScore());
        node.setRiskRejected(node.getLeadershipOnly() || containsRisk(leader.getTradableReason()) || containsRisk(leader.getRetentionReason()));
        node.setRejectionReason(defaultString(leader.getTradableReason(), leader.getRetentionReason()));
        node.setSafetyNote("leadership observability replay-only; not a tradable candidate");
        node.setAiGovernanceSummary(governanceSummary(node));
        node.setPayloadJson(leader.getPayloadJson());
        return node;
    }

    private ThemeReplayNodeEntity fromRetainedLeader(ThemeLeaderRetentionEntity leader) {
        ThemeReplayNodeEntity node = baseNode(leader.getTradingDate(), leader.getThemeTag(), leader.getSymbol(), leader.getStockName());
        node.setCandidateRole("THEME_LEADER");
        node.setResearchRole("THEME_LEADER");
        node.setIsThemeLeader(true);
        node.setLeadershipOnly(!Boolean.TRUE.equals(leader.getLeaderTradable()));
        node.setThemeLeaderSymbol(leader.getSymbol());
        node.setLeaderTradable(Boolean.TRUE.equals(leader.getLeaderTradable()));
        node.setThemeImportanceScore(leader.getScore());
        node.setShadowRankScore(leader.getScore());
        node.setRiskRejected(node.getLeadershipOnly() || containsRisk(leader.getRetentionReason()));
        node.setRejectionReason(leader.getRetentionReason());
        node.setSafetyNote("retained leader replay-only; not FinalDecision tradable candidate");
        node.setAiGovernanceSummary(governanceSummary(node));
        node.setPayloadJson(leader.getPayloadJson());
        return node;
    }

    private ThemeReplayNodeEntity fromPeerShadow(ThemePeerShadowCandidateEntity peer) {
        ThemeReplayNodeEntity node = baseNode(peer.getTradingDate(), peer.getThemeTag(), peer.getSymbol(), peer.getStockName());
        node.setCandidateRole(peer.getCandidateRole());
        node.setResearchRole("PEER_SHADOW");
        node.setIsThemeLeader(false);
        node.setLeadershipOnly(false);
        node.setThemeLeaderSymbol(peer.getLeaderSymbol());
        node.setLeaderTradable(false);
        node.setThemeImportanceScore(peer.getThemeImportanceScore());
        node.setTradableScore(peer.getTradableScore());
        node.setShadowRankScore(peer.getShadowRankScore());
        node.setRiskRejected(false);
        node.setRejectionReason(peer.getRejectionReason());
        node.setSafetyNote("peer shadow/replay context only; not allowed/tradable universe");
        node.setAiGovernanceSummary(governanceSummary(node));
        node.setPayloadJson(peer.getEvidenceJson());
        return node;
    }

    private ThemeReplayNodeEntity baseNode(LocalDate date, String themeTag, String symbol, String stockName) {
        ThemeReplayNodeEntity node = new ThemeReplayNodeEntity();
        node.setTradingDate(date);
        node.setThemeTag(defaultString(themeTag, "UNKNOWN"));
        node.setSymbol(symbol);
        node.setStockName(stockName);
        node.setResearchUniverse(true);
        node.setTradableUniverse(false);
        return node;
    }

    private void addNode(Map<String, LinkedHashMap<String, ThemeReplayNodeEntity>> grouped, ThemeReplayNodeEntity node) {
        if (node == null || node.getSymbol() == null) return;
        grouped.computeIfAbsent(node.getThemeTag(), ignored -> new LinkedHashMap<>())
                .merge(node.getSymbol(), node, this::preferRicherNode);
    }

    private ThemeReplayNodeEntity preferRicherNode(ThemeReplayNodeEntity existing, ThemeReplayNodeEntity incoming) {
        if (Boolean.TRUE.equals(existing.getIsThemeLeader())) return existing;
        if (Boolean.TRUE.equals(incoming.getIsThemeLeader())) return incoming;
        if (existing.getShadowRankScore() == null && incoming.getShadowRankScore() != null) return incoming;
        return existing;
    }

    private List<ThemeReplayEdgeEntity> buildEdges(List<ThemeReplayNodeEntity> nodes) {
        Map<String, ThemeReplayNodeEntity> byKey = nodes.stream()
                .collect(Collectors.toMap(n -> n.getThemeTag() + "|" + n.getSymbol(), n -> n, (a, b) -> a, LinkedHashMap::new));
        List<ThemeReplayEdgeEntity> edges = new ArrayList<>();
        for (ThemeReplayNodeEntity node : nodes) {
            if (node.getThemeLeaderSymbol() == null || node.getThemeLeaderSymbol().equals(node.getSymbol())) continue;
            ThemeReplayNodeEntity leader = byKey.get(node.getThemeTag() + "|" + node.getThemeLeaderSymbol());
            if (leader == null) continue;
            ThemeReplayEdgeEntity edge = new ThemeReplayEdgeEntity();
            edge.setTradingDate(node.getTradingDate());
            edge.setThemeTag(node.getThemeTag());
            edge.setFromSymbol(node.getThemeLeaderSymbol());
            edge.setToSymbol(node.getSymbol());
            edge.setEdgeType("PEER_SHADOW".equals(node.getResearchRole()) ? "LEADER_TO_PEER" : "PEER_SPREAD");
            edge.setConfidence(node.getShadowRankScore() == null ? new BigDecimal("0.5000") : node.getShadowRankScore().divide(new BigDecimal("10"), 4, java.math.RoundingMode.HALF_UP));
            edge.setReason("Replay-only theme timeline link; does not promote research universe to tradable universe");
            edges.add(edge);
        }
        return edges;
    }

    private List<ThemeReplaySnapshotEntity> buildSnapshots(LocalDate date, Map<String, LinkedHashMap<String, ThemeReplayNodeEntity>> groupedNodes, List<ThemeReplayEdgeEntity> edges) {
        List<ThemeReplaySnapshotEntity> snapshots = new ArrayList<>();
        for (var entry : groupedNodes.entrySet()) {
            String theme = entry.getKey();
            List<ThemeReplayNodeEntity> nodes = new ArrayList<>(entry.getValue().values());
            ThemeReplaySnapshotEntity snapshot = new ThemeReplaySnapshotEntity();
            snapshot.setTradingDate(date);
            snapshot.setThemeTag(theme);
            snapshot.setLifecycleStage("REPLAY_ONLY");
            snapshot.setLeaderSymbol(nodes.stream().filter(n -> Boolean.TRUE.equals(n.getIsThemeLeader())).map(ThemeReplayNodeEntity::getSymbol).findFirst().orElse(null));
            snapshot.setLeaderCount((int) nodes.stream().filter(n -> Boolean.TRUE.equals(n.getIsThemeLeader())).count());
            snapshot.setPeerCount((int) nodes.stream().filter(n -> "PEER_SHADOW".equals(n.getResearchRole())).count());
            snapshot.setBreadth(nodes.size());
            snapshot.setTaxonomyGapCount((int) nodes.stream().filter(n -> "TAXONOMY_GAP".equals(n.getResearchRole())).count());
            snapshot.setDivergenceCount((int) nodes.stream().filter(n -> "DIVERGENCE".equals(n.getResearchRole())).count());
            snapshot.setRiskRejectedCount((int) nodes.stream().filter(n -> Boolean.TRUE.equals(n.getRiskRejected())).count());
            snapshot.setResearchUniverseCount((int) nodes.stream().filter(n -> Boolean.TRUE.equals(n.getResearchUniverse())).count());
            snapshot.setTradableUniverseCount((int) nodes.stream().filter(n -> Boolean.TRUE.equals(n.getTradableUniverse())).count());
            snapshot.setReplayScore(nodes.stream().map(ThemeReplayNodeEntity::getShadowRankScore).filter(Objects::nonNull).max(BigDecimal::compareTo).orElse(null));
            snapshot.setPayloadJson(json(Map.of(
                    "shadowOnly", true,
                    "replayOnly", true,
                    "doesNotAffectFinalDecision", true,
                    "researchUniverseNotTradable", true,
                    "edgeCount", edges.stream().filter(e -> theme.equals(e.getThemeTag())).count()
            )));
            snapshots.add(snapshot);
        }
        return snapshots;
    }

    private String researchRole(String candidateRole, boolean isLeader) {
        if (isLeader || "THEME_LEADER".equals(candidateRole)) return "THEME_LEADER";
        if (candidateRole != null && candidateRole.contains("PEER_SHADOW")) return "PEER_SHADOW";
        if (candidateRole != null && candidateRole.contains("TAXONOMY")) return "TAXONOMY_GAP";
        if (candidateRole != null && candidateRole.contains("DIVERGENCE")) return "DIVERGENCE";
        return "REPLAY_CANDIDATE";
    }

    private boolean containsRisk(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("risk") || lower.contains("not finaldecision") || lower.contains("chase-high") || lower.contains("不得");
    }

    private String governanceSummary(ThemeReplayNodeEntity node) {
        if (Boolean.TRUE.equals(node.getLeadershipOnly())) {
            return "Leadership-only replay node: retained for market/theme context; must not enter tradable universe or bypass risk gates.";
        }
        if ("PEER_SHADOW".equals(node.getResearchRole())) {
            return "Peer shadow replay node: research universe only; must not expand allowed/tradable universe.";
        }
        return "Replay-only research node; no BUY/SELL/ENTER or FinalDecision side effect.";
    }

    private List<ThemeReplayTimelineResponse.Event> events(List<ThemeReplayTimelineResponse.Node> nodes, List<ThemeReplayTimelineResponse.Edge> edges) {
        List<ThemeReplayTimelineResponse.Event> events = new ArrayList<>();
        if (!nodes.isEmpty()) events.add(new ThemeReplayTimelineResponse.Event("THEME_EMERGED", null, "Theme replay timeline created", null));
        nodes.stream().filter(ThemeReplayTimelineResponse.Node::isThemeLeader).findFirst()
                .ifPresent(n -> events.add(new ThemeReplayTimelineResponse.Event("LEADER_IDENTIFIED", n.symbol(), "Theme leader identified for replay", null)));
        edges.stream().filter(e -> "LEADER_TO_PEER".equals(e.edgeType())).findFirst()
                .ifPresent(e -> events.add(new ThemeReplayTimelineResponse.Event("PEER_DISCOVERED", e.toSymbol(), "Peer shadow discovered from retained leader", null)));
        nodes.stream().filter(n -> "DIVERGENCE".equals(n.researchRole())).findFirst()
                .ifPresent(n -> events.add(new ThemeReplayTimelineResponse.Event("DIVERGENCE_DETECTED", n.symbol(), "Divergence replay node detected", null)));
        nodes.stream().filter(n -> "TAXONOMY_GAP".equals(n.researchRole())).findFirst()
                .ifPresent(n -> events.add(new ThemeReplayTimelineResponse.Event("TAXONOMY_GAP_DETECTED", n.symbol(), "Taxonomy gap replay node detected", null)));
        if (nodes.stream().anyMatch(n -> n.aiGovernanceSummary() != null && !n.aiGovernanceSummary().isBlank())) {
            events.add(new ThemeReplayTimelineResponse.Event("AI_GOVERNANCE_ANALYZED", null, "AI governance summary attached to replay nodes", null));
        }
        nodes.stream().filter(ThemeReplayTimelineResponse.Node::riskRejected).findFirst()
                .ifPresent(n -> events.add(new ThemeReplayTimelineResponse.Event("RISK_REJECTED", n.symbol(), defaultString(n.rejectionReason(), "risk gate blocked"), null)));
        if (nodes.stream().noneMatch(ThemeReplayTimelineResponse.Node::tradableUniverse)) {
            events.add(new ThemeReplayTimelineResponse.Event("FINAL_DECISION_REST", null, "No replay node is tradable; FinalDecision remains unaffected", null));
        }
        return events;
    }

    private ThemeReplaySummaryResponse toSummary(ThemeReplaySnapshotEntity e) {
        return new ThemeReplaySummaryResponse(
                e.getTradingDate(), e.getThemeTag(), e.getLifecycleStage(), e.getLeaderSymbol(),
                intValue(e.getLeaderCount()), intValue(e.getPeerCount()), intValue(e.getBreadth()),
                intValue(e.getTaxonomyGapCount()), intValue(e.getDivergenceCount()), intValue(e.getRiskRejectedCount()),
                intValue(e.getResearchUniverseCount()), intValue(e.getTradableUniverseCount()), e.getReplayScore(),
                true, true, SAFETY);
    }

    private ThemeReplayTimelineResponse.Node toNode(ThemeReplayNodeEntity e) {
        return new ThemeReplayTimelineResponse.Node(
                e.getSymbol(), e.getStockName(), e.getResearchRole(), e.getCandidateRole(),
                Boolean.TRUE.equals(e.getIsThemeLeader()), Boolean.TRUE.equals(e.getLeadershipOnly()), e.getThemeLeaderSymbol(),
                Boolean.TRUE.equals(e.getResearchUniverse()), Boolean.TRUE.equals(e.getTradableUniverse()), Boolean.TRUE.equals(e.getLeaderTradable()),
                e.getThemeImportanceScore(), e.getTradableScore(), e.getShadowRankScore(), e.getDivergenceScore(), e.getTaxonomyGapScore(),
                Boolean.TRUE.equals(e.getRiskRejected()), e.getRejectionReason(), e.getSafetyNote(), e.getAiGovernanceSummary(), e.getPayloadJson());
    }

    private ThemeReplayTimelineResponse.Edge toEdge(ThemeReplayEdgeEntity e) {
        return new ThemeReplayTimelineResponse.Edge(e.getFromSymbol(), e.getToSymbol(), e.getEdgeType(), e.getConfidence(), e.getReason(), e.getPayloadJson());
    }

    private int intValue(Integer value) { return value == null ? 0 : value; }
    private String defaultString(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private <T> List<T> safeList(List<T> list) { return list == null ? List.of() : list; }
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { return "{}"; }
    }
}
