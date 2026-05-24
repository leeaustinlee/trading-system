package com.austin.trading.service;

import com.austin.trading.dto.response.ResearchUniverseResponse;
import com.austin.trading.dto.response.ThemeReplayMetricsResponse;
import com.austin.trading.entity.*;
import com.austin.trading.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ResearchUniverseService {

    private static final ResearchUniverseResponse.SafetyBoundary SAFETY = ResearchUniverseResponse.SafetyBoundary.researchOnlyBoundary();
    private static final String SHADOW_ONLY = "SHADOW_ONLY";
    private static final String SAFETY_NOTE = "research-universe/shadow-only; does not affect FinalDecision, BUY/SELL/ENTER, candidate gates, risk gates, or production scores; promotion requires explicit future governance review";

    private final ResearchUniverseItemRepository researchRepository;
    private final ThemeLeadershipSnapshotRepository leadershipSnapshotRepository;
    private final ThemeLeaderRetentionRepository leaderRetentionRepository;
    private final ThemePeerShadowCandidateRepository peerShadowCandidateRepository;
    private final ThemeReplayNodeRepository replayNodeRepository;
    private final ThemeLifecycleStateRepository lifecycleStateRepository;
    private final ReplayMetricsService replayMetricsService;
    private final CandidateStockRepository candidateStockRepository;

    public ResearchUniverseService(
            ResearchUniverseItemRepository researchRepository,
            ThemeLeadershipSnapshotRepository leadershipSnapshotRepository,
            ThemeLeaderRetentionRepository leaderRetentionRepository,
            ThemePeerShadowCandidateRepository peerShadowCandidateRepository,
            ThemeReplayNodeRepository replayNodeRepository,
            CandidateStockRepository candidateStockRepository
    ) {
        this(researchRepository, leadershipSnapshotRepository, leaderRetentionRepository, peerShadowCandidateRepository,
                replayNodeRepository, null, null, candidateStockRepository);
    }

    @Autowired
    public ResearchUniverseService(
            ResearchUniverseItemRepository researchRepository,
            ThemeLeadershipSnapshotRepository leadershipSnapshotRepository,
            ThemeLeaderRetentionRepository leaderRetentionRepository,
            ThemePeerShadowCandidateRepository peerShadowCandidateRepository,
            ThemeReplayNodeRepository replayNodeRepository,
            ThemeLifecycleStateRepository lifecycleStateRepository,
            ReplayMetricsService replayMetricsService,
            CandidateStockRepository candidateStockRepository
    ) {
        this.researchRepository = researchRepository;
        this.leadershipSnapshotRepository = leadershipSnapshotRepository;
        this.leaderRetentionRepository = leaderRetentionRepository;
        this.peerShadowCandidateRepository = peerShadowCandidateRepository;
        this.replayNodeRepository = replayNodeRepository;
        this.lifecycleStateRepository = lifecycleStateRepository;
        this.replayMetricsService = replayMetricsService;
        this.candidateStockRepository = candidateStockRepository;
    }

    @Transactional
    public ResearchUniverseResponse build(LocalDate date) {
        List<ResearchUniverseItemEntity> items = aggregate(date);
        researchRepository.deleteByTradingDate(date);
        researchRepository.saveAll(items);
        return response(date, items);
    }

    @Transactional(readOnly = true)
    public ResearchUniverseResponse get(LocalDate date) {
        return response(date, safeList(researchRepository.findByTradingDateOrderByThemeTagAscSymbolAscSourceAsc(date)));
    }

    @Transactional(readOnly = true)
    public ResearchUniverseResponse byTheme(LocalDate date, String themeTag) {
        return response(date, safeList(researchRepository.findByTradingDateAndThemeTagOrderBySymbolAscSourceAsc(date, themeTag)));
    }

    @Transactional(readOnly = true)
    public ResearchUniverseResponse bySymbol(LocalDate date, String symbol) {
        return response(date, safeList(researchRepository.findByTradingDateAndSymbolOrderByThemeTagAscSourceAsc(date, symbol)));
    }

    @Transactional(readOnly = true)
    public ResearchUniverseResponse.GovernanceSummary governanceSummary(LocalDate date) {
        List<ResearchUniverseItemEntity> items = safeList(researchRepository.findByTradingDateOrderByThemeTagAscSymbolAscSourceAsc(date));
        Map<String, Long> statusCounts = items.stream().collect(Collectors.groupingBy(ResearchUniverseItemEntity::getGovernanceStatus, TreeMap::new, Collectors.counting()));
        Map<String, Long> roleCounts = items.stream().collect(Collectors.groupingBy(ResearchUniverseItemEntity::getResearchRole, TreeMap::new, Collectors.counting()));
        return new ResearchUniverseResponse.GovernanceSummary(
                date,
                items.size(),
                items.stream().filter(i -> Boolean.TRUE.equals(i.getResearchUniverse())).count(),
                items.stream().filter(i -> Boolean.TRUE.equals(i.getTradableUniverse())).count(),
                items.stream().filter(i -> Boolean.TRUE.equals(i.getPromotedToTradable())).count(),
                statusCounts,
                roleCounts,
                SAFETY,
                true,
                true
        );
    }

    private List<ResearchUniverseItemEntity> aggregate(LocalDate date) {
        LinkedHashMap<String, ResearchUniverseItemEntity> items = new LinkedHashMap<>();
        safeList(leadershipSnapshotRepository.findByTradingDateOrderByLeaderRankAsc(date))
                .forEach(e -> put(items, fromLeadershipSnapshot(e)));
        safeList(leaderRetentionRepository.findByTargetPhaseAndActiveTrueAndTradingDateLessThanEqualOrderByTradingDateDescLeaderRankAsc("OPENING", date)).stream()
                .filter(e -> date.equals(e.getTradingDate()))
                .forEach(e -> put(items, fromRetainedLeader(e)));
        safeList(peerShadowCandidateRepository.findByTradingDateOrderBySourcePhaseAscLeaderSymbolAscShadowRankScoreDesc(date))
                .forEach(e -> put(items, fromPeerShadow(e)));
        safeList(replayNodeRepository.findByTradingDateOrderByThemeTagAscSymbolAsc(date))
                .forEach(e -> put(items, fromReplayNode(e)));
        safeList(candidateStockRepository.findByTradingDateOrderByScoreDesc(date, Pageable.unpaged())).stream()
                .filter(this::isResearchCandidateRole)
                .forEach(e -> put(items, fromCandidate(e)));
        List<ResearchUniverseItemEntity> sorted = items.values().stream()
                .sorted(Comparator.comparing(ResearchUniverseItemEntity::getThemeTag, Comparator.nullsLast(String::compareTo))
                        .thenComparing(ResearchUniverseItemEntity::getSymbol, Comparator.nullsLast(String::compareTo))
                        .thenComparing(ResearchUniverseItemEntity::getSource, Comparator.nullsLast(String::compareTo)))
                .toList();
        attachLifecycleAdvisory(date, sorted);
        return sorted;
    }

    private ResearchUniverseItemEntity fromLeadershipSnapshot(ThemeLeadershipSnapshotEntity leader) {
        ResearchUniverseItemEntity item = base(leader.getTradingDate(), leader.getSymbol(), leader.getStockName(), leader.getThemeTag(), "leadership");
        item.setResearchRole(Boolean.TRUE.equals(leader.getTradable()) ? "LEADER" : "LEADERSHIP_ONLY");
        item.setResearchScore(leader.getScore());
        item.setThemeImportanceScore(leader.getScore());
        item.setThemeLeaderSymbol(leader.getSymbol());
        item.setLeadershipOnly(!Boolean.TRUE.equals(leader.getTradable()));
        item.setLeaderTradable(Boolean.TRUE.equals(leader.getTradable()));
        item.setBlockedReason(defaultString(leader.getTradableReason(), leader.getRetentionReason()));
        item.setPayloadJson(defaultString(leader.getPayloadJson(), leader.getDivergenceFlagsJson()));
        return enforceResearchOnly(item);
    }

    private ResearchUniverseItemEntity fromRetainedLeader(ThemeLeaderRetentionEntity leader) {
        ResearchUniverseItemEntity item = base(leader.getTradingDate(), leader.getSymbol(), leader.getStockName(), leader.getThemeTag(), "leadership");
        item.setResearchRole(Boolean.TRUE.equals(leader.getLeaderTradable()) ? "LEADER" : "LEADERSHIP_ONLY");
        item.setResearchScore(leader.getScore());
        item.setThemeImportanceScore(leader.getScore());
        item.setThemeLeaderSymbol(leader.getSymbol());
        item.setLeadershipOnly(!Boolean.TRUE.equals(leader.getLeaderTradable()));
        item.setLeaderTradable(Boolean.TRUE.equals(leader.getLeaderTradable()));
        item.setBlockedReason(leader.getRetentionReason());
        item.setPayloadJson(leader.getPayloadJson());
        return enforceResearchOnly(item);
    }

    private ResearchUniverseItemEntity fromPeerShadow(ThemePeerShadowCandidateEntity peer) {
        ResearchUniverseItemEntity item = base(peer.getTradingDate(), peer.getSymbol(), peer.getStockName(), peer.getThemeTag(), "peer_shadow");
        item.setResearchRole("PEER_SHADOW");
        item.setCandidateRole(peer.getCandidateRole());
        item.setResearchScore(peer.getShadowRankScore());
        item.setThemeImportanceScore(peer.getThemeImportanceScore());
        item.setTradableScore(peer.getTradableScore());
        item.setThemeLeaderSymbol(peer.getLeaderSymbol());
        item.setLeaderTradable(false);
        item.setBlockedReason(peer.getRejectionReason());
        item.setPayloadJson(peer.getEvidenceJson());
        return enforceResearchOnly(item);
    }

    private ResearchUniverseItemEntity fromReplayNode(ThemeReplayNodeEntity node) {
        ResearchUniverseItemEntity item = base(node.getTradingDate(), node.getSymbol(), node.getStockName(), node.getThemeTag(), "theme_replay");
        item.setResearchRole(normalizeRole(node.getResearchRole(), node.getCandidateRole(), Boolean.TRUE.equals(node.getIsThemeLeader()), Boolean.TRUE.equals(node.getLeadershipOnly())));
        item.setCandidateRole(node.getCandidateRole());
        item.setResearchScore(firstNonNull(node.getShadowRankScore(), node.getThemeImportanceScore(), node.getDivergenceScore(), node.getTaxonomyGapScore()));
        item.setThemeImportanceScore(node.getThemeImportanceScore());
        item.setTradableScore(node.getTradableScore());
        item.setThemeLeaderSymbol(node.getThemeLeaderSymbol());
        item.setLeadershipOnly(Boolean.TRUE.equals(node.getLeadershipOnly()));
        item.setLeaderTradable(Boolean.TRUE.equals(node.getLeaderTradable()));
        item.setBlockedReason(node.getRejectionReason());
        item.setPayloadJson(node.getPayloadJson());
        return enforceResearchOnly(item);
    }

    private ResearchUniverseItemEntity fromCandidate(CandidateStockEntity candidate) {
        ResearchUniverseItemEntity item = base(candidate.getTradingDate(), candidate.getSymbol(), candidate.getStockName(), candidate.getThemeTag(), sourceForCandidate(candidate.getCandidateRole()));
        item.setResearchRole(normalizeRole(null, candidate.getCandidateRole(), Boolean.TRUE.equals(candidate.getIsThemeLeader()), !Boolean.TRUE.equals(candidate.getLeaderTradable()) && Boolean.TRUE.equals(candidate.getIsThemeLeader())));
        item.setCandidateRole(candidate.getCandidateRole());
        item.setResearchScore(firstNonNull(candidate.getShadowRankScore(), candidate.getThemeImportanceScore(), candidate.getScore()));
        item.setThemeImportanceScore(candidate.getThemeImportanceScore());
        item.setTradableScore(candidate.getTradableScore());
        item.setThemeLeaderSymbol(defaultString(candidate.getThemeLeaderSymbol(), Boolean.TRUE.equals(candidate.getIsThemeLeader()) ? candidate.getSymbol() : null));
        item.setLeadershipOnly(Boolean.TRUE.equals(candidate.getIsThemeLeader()) && !Boolean.TRUE.equals(candidate.getLeaderTradable()));
        item.setLeaderTradable(Boolean.TRUE.equals(candidate.getLeaderTradable()));
        item.setBlockedReason(candidate.getLeaderRetentionReason());
        item.setPayloadJson(candidate.getPayloadJson());
        return enforceResearchOnly(item);
    }

    private ResearchUniverseItemEntity base(LocalDate date, String symbol, String stockName, String themeTag, String source) {
        ResearchUniverseItemEntity item = new ResearchUniverseItemEntity();
        item.setTradingDate(date);
        item.setSymbol(symbol);
        item.setStockName(stockName);
        item.setThemeTag(defaultString(themeTag, "UNKNOWN"));
        item.setSource(source);
        item.setSafetyNote(SAFETY_NOTE);
        return item;
    }

    private ResearchUniverseItemEntity enforceResearchOnly(ResearchUniverseItemEntity item) {
        item.setGovernanceStatus(SHADOW_ONLY);
        item.setResearchUniverse(true);
        item.setTradableUniverse(false);
        item.setPromotedToTradable(false);
        item.setPromotionReason(null);
        if (item.getResearchRole() == null) item.setResearchRole("REPLAY_CANDIDATE");
        if (item.getLeadershipOnly() == null) item.setLeadershipOnly(false);
        if (item.getLeaderTradable() == null) item.setLeaderTradable(false);
        return item;
    }

    private boolean isResearchCandidateRole(CandidateStockEntity candidate) {
        String role = candidate.getCandidateRole();
        return Boolean.TRUE.equals(candidate.getIsThemeLeader())
                || contains(role, "PEER_SHADOW")
                || contains(role, "DIVERGENCE")
                || contains(role, "TAXONOMY")
                || contains(role, "REPLAY")
                || contains(role, "NARRATIVE")
                || contains(role, "KOL");
    }

    private String normalizeRole(String researchRole, String candidateRole, boolean isLeader, boolean leadershipOnly) {
        if (leadershipOnly) return "LEADERSHIP_ONLY";
        if (isLeader || "THEME_LEADER".equals(researchRole) || "THEME_LEADER".equals(candidateRole)) return "LEADER";
        if (contains(researchRole, "PEER_SHADOW") || contains(candidateRole, "PEER_SHADOW")) return "PEER_SHADOW";
        if (contains(researchRole, "DIVERGENCE") || contains(candidateRole, "DIVERGENCE")) return "DIVERGENCE";
        if (contains(researchRole, "TAXONOMY") || contains(candidateRole, "TAXONOMY")) return "TAXONOMY_GAP";
        if (contains(researchRole, "NARRATIVE") || contains(candidateRole, "NARRATIVE")) return "NARRATIVE_SHADOW";
        if (contains(researchRole, "KOL") || contains(candidateRole, "KOL")) return "KOL_SHADOW";
        return "REPLAY_CANDIDATE";
    }

    private String sourceForCandidate(String candidateRole) {
        if (contains(candidateRole, "DIVERGENCE")) return "divergence";
        if (contains(candidateRole, "TAXONOMY")) return "taxonomy_gap";
        if (contains(candidateRole, "NARRATIVE")) return "narrative";
        if (contains(candidateRole, "KOL")) return "narrative";
        if (contains(candidateRole, "PEER_SHADOW")) return "peer_shadow";
        return "candidate_scan";
    }

    private void put(Map<String, ResearchUniverseItemEntity> map, ResearchUniverseItemEntity item) {
        if (item == null || item.getTradingDate() == null || item.getSymbol() == null) return;
        map.putIfAbsent(item.getTradingDate() + "|" + item.getSymbol() + "|" + item.getThemeTag() + "|" + item.getSource(), item);
    }


    private ThemeReplayMetricsResponse.MetricsSummary metricsSummary(LocalDate date, List<ResearchUniverseItemEntity> items) {
        if (replayMetricsService == null || items == null || items.isEmpty()) {
            return ThemeReplayMetricsResponse.MetricsSummary.empty();
        }
        String theme = items.get(0).getThemeTag();
        boolean singleTheme = items.stream().allMatch(i -> Objects.equals(theme, i.getThemeTag()));
        return singleTheme ? replayMetricsService.summary(date, theme) : ThemeReplayMetricsResponse.MetricsSummary.empty();
    }

    private ResearchUniverseResponse response(LocalDate date, List<ResearchUniverseItemEntity> items) {
        attachLifecycleAdvisory(date, items);
        return new ResearchUniverseResponse(date, true, true, SAFETY, metricsSummary(date, items), items.stream().map(this::toItem).toList());
    }

    private void attachLifecycleAdvisory(LocalDate date, List<ResearchUniverseItemEntity> items) {
        if (items == null || items.isEmpty() || lifecycleStateRepository == null) return;
        Map<String, ThemeLifecycleStateEntity> lifecycleByTheme = safeList(lifecycleStateRepository.findByTradingDateOrderByThemeTagAsc(date)).stream()
                .collect(Collectors.toMap(ThemeLifecycleStateEntity::getThemeTag, Function.identity(), (a, b) -> a));
        for (ResearchUniverseItemEntity item : items) {
            ThemeLifecycleStateEntity lifecycle = lifecycleByTheme.get(item.getThemeTag());
            if (lifecycle == null) continue;
            item.setLifecycleStage(lifecycle.getStage());
            item.setLifecycleScore(lifecycle.getLifecycleScore());
            item.setLifecycleAdvisory(lifecycle.getReason() + " | advisory-only; lifecycle does not promote research universe or override risk gate");
        }
    }

    private ResearchUniverseResponse.Item toItem(ResearchUniverseItemEntity e) {
        return new ResearchUniverseResponse.Item(
                e.getSymbol(), e.getStockName(), e.getThemeTag(), e.getResearchRole(), e.getSource(), e.getResearchScore(),
                e.getThemeImportanceScore(), e.getTradableScore(), e.getNarrativeDensityScore(), e.getLifecycleStage(),
                e.getLifecycleScore(), e.getLifecycleAdvisory(), e.getGovernanceStatus(),
                Boolean.TRUE.equals(e.getResearchUniverse()), Boolean.TRUE.equals(e.getTradableUniverse()), Boolean.TRUE.equals(e.getPromotedToTradable()),
                e.getPromotionReason(), e.getBlockedReason(), e.getCandidateRole(), e.getThemeLeaderSymbol(),
                Boolean.TRUE.equals(e.getLeadershipOnly()), Boolean.TRUE.equals(e.getLeaderTradable()), e.getSafetyNote(), e.getPayloadJson(), SAFETY);
    }

    private ResearchUniverseItemEntity fromResponseItem(ResearchUniverseResponse.Item i) {
        ResearchUniverseItemEntity e = base(null, i.symbol(), i.stockName(), i.themeTag(), i.source());
        e.setResearchRole(i.researchRole());
        e.setResearchScore(i.researchScore());
        e.setThemeImportanceScore(i.themeImportanceScore());
        e.setTradableScore(i.tradableScore());
        e.setNarrativeDensityScore(i.narrativeDensityScore());
        e.setLifecycleStage(i.lifecycleStage());
        e.setLifecycleScore(i.lifecycleScore());
        e.setLifecycleAdvisory(i.lifecycleAdvisory());
        e.setGovernanceStatus(i.governanceStatus());
        e.setResearchUniverse(i.researchUniverse());
        e.setTradableUniverse(i.tradableUniverse());
        e.setPromotedToTradable(i.promotedToTradable());
        e.setPromotionReason(i.promotionReason());
        e.setBlockedReason(i.blockedReason());
        e.setCandidateRole(i.candidateRole());
        e.setThemeLeaderSymbol(i.themeLeaderSymbol());
        e.setLeadershipOnly(i.leadershipOnly());
        e.setLeaderTradable(i.leaderTradable());
        e.setPayloadJson(i.payloadJson());
        return e;
    }

    private boolean contains(String value, String token) {
        return value != null && value.toUpperCase(Locale.ROOT).contains(token);
    }

    private BigDecimal firstNonNull(BigDecimal... values) {
        for (BigDecimal value : values) if (value != null) return value;
        return null;
    }

    private String defaultString(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private <T> List<T> safeList(List<T> list) { return list == null ? List.of() : list; }
}
