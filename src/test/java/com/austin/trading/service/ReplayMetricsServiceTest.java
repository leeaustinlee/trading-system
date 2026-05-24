package com.austin.trading.service;

import com.austin.trading.entity.*;
import com.austin.trading.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReplayMetricsServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 22);
    private static final String THEME = "被動元件";
    private static final String FIXTURE = "replay/theme-first/passive-components-2026-05-22.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ThemeReplayMetricsRepository metricsRepository;
    private ThemeReplaySnapshotRepository snapshotRepository;
    private ThemeReplayNodeRepository nodeRepository;
    private ThemeReplayEdgeRepository edgeRepository;
    private ThemeLifecycleStateRepository lifecycleRepository;
    private ResearchUniverseItemRepository researchRepository;
    private CandidateStockRepository candidateStockRepository;
    private FinalDecisionRepository finalDecisionRepository;
    private ReplayMetricsService service;

    @BeforeEach
    void setUp() throws Exception {
        metricsRepository = mock(ThemeReplayMetricsRepository.class);
        snapshotRepository = mock(ThemeReplaySnapshotRepository.class);
        nodeRepository = mock(ThemeReplayNodeRepository.class);
        edgeRepository = mock(ThemeReplayEdgeRepository.class);
        lifecycleRepository = mock(ThemeLifecycleStateRepository.class);
        researchRepository = mock(ResearchUniverseItemRepository.class);
        candidateStockRepository = mock(CandidateStockRepository.class);
        finalDecisionRepository = mock(FinalDecisionRepository.class);
        service = new ReplayMetricsService(metricsRepository, snapshotRepository, nodeRepository, edgeRepository,
                lifecycleRepository, researchRepository, candidateStockRepository, finalDecisionRepository, objectMapper);

        when(snapshotRepository.findByTradingDateOrderByThemeTagAsc(DATE)).thenReturn(List.of(snapshot()));
        when(nodeRepository.findByTradingDateAndThemeTagOrderByIdAsc(DATE, THEME)).thenReturn(fixtureNodes());
        when(edgeRepository.findByTradingDateAndThemeTagOrderByIdAsc(DATE, THEME)).thenReturn(fixtureEdges());
        when(researchRepository.findByTradingDateAndThemeTagOrderBySymbolAscSourceAsc(DATE, THEME)).thenReturn(fixtureResearch());
        when(candidateStockRepository.findByTradingDateOrderByScoreDesc(eq(DATE), any(Pageable.class))).thenReturn(List.of());
        when(lifecycleRepository.findByTradingDateAndThemeTag(DATE, THEME)).thenReturn(Optional.of(lifecycle()));
        when(finalDecisionRepository.findTopByTradingDateOrderByCreatedAtDesc(DATE)).thenReturn(Optional.empty());
        when(metricsRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void yageoPassiveComponentsMetricsDetectLeaderPeersAndSafetyZero() throws Exception {
        var result = service.build(DATE);

        assertThat(result.replayOnly()).isTrue();
        assertThat(result.analyticsOnly()).isTrue();
        assertThat(result.noAutoPromotion()).isTrue();
        assertThat(result.builtCount()).isEqualTo(1);
        var item = result.items().get(0);
        assertThat(item.themeTag()).isEqualTo(THEME);
        assertThat(item.leaderRetentionRate()).isGreaterThan(BigDecimal.ZERO);
        assertThat(item.peerDiscoveryHitRate()).isGreaterThan(BigDecimal.ZERO);
        assertThat(item.candidateDiversification()).isGreaterThan(1);
        assertThat(item.riskGateBypassCount()).isZero();
        assertThat(item.leadershipOnlyEnteredCount()).isZero();
        assertThat(item.leaderTradableFalseEnterCount()).isZero();
        assertThat(item.researchVsTradableSeparationViolationCount()).isZero();
        assertThat(item.emergingToMainstreamHitRate()).isEqualByComparingTo("1.0000");
        assertThat(item.safetyBoundary().doesNotAffectFinalDecision()).isTrue();
        assertThat(item.safetyBoundary().metricsDoNotOverrideRiskGate()).isTrue();

        verify(metricsRepository).deleteByTradingDate(DATE);
        verify(metricsRepository).flush();
        verify(metricsRepository).saveAll(any());
        verify(candidateStockRepository, never()).save(any());
        verify(finalDecisionRepository, never()).save(any());
    }

    @Test
    void summaryReadsPersistedMetricsWithoutProductionSideEffects() {
        ThemeReplayMetricsEntity metric = new ThemeReplayMetricsEntity();
        metric.setTradingDate(DATE);
        metric.setThemeTag(THEME);
        metric.setLeaderRetentionRate(new BigDecimal("1.0000"));
        metric.setPeerDiscoveryHitRate(new BigDecimal("0.8000"));
        metric.setCandidateDiversification(4);
        when(metricsRepository.findByTradingDateAndThemeTag(DATE, THEME)).thenReturn(Optional.of(metric));

        var summary = service.summary(DATE, THEME);

        assertThat(summary.leaderRetentionRate()).isEqualByComparingTo("1.0000");
        assertThat(summary.peerDiscoveryHitRate()).isEqualByComparingTo("0.8000");
        assertThat(summary.candidateDiversification()).isEqualTo(4);
        assertThat(summary.riskGateBypassCount()).isZero();
        verify(candidateStockRepository, never()).save(any());
        verify(finalDecisionRepository, never()).save(any());
    }

    protected ThemeReplaySnapshotEntity snapshot() {
        ThemeReplaySnapshotEntity s = new ThemeReplaySnapshotEntity();
        s.setTradingDate(DATE);
        s.setThemeTag(THEME);
        s.setLeaderSymbol("2327");
        s.setLeaderCount(1);
        s.setPeerCount(5);
        s.setBreadth(6);
        s.setResearchUniverseCount(6);
        s.setTradableUniverseCount(0);
        s.setLifecycleStage(ThemeLifecycleEngine.MAINSTREAM);
        s.setPayloadJson("{\"postSignalReturn1d\":0.015,\"postSignalReturn3d\":0.045,\"postSignalReturn5d\":0.072}");
        return s;
    }

    protected ThemeLifecycleStateEntity lifecycle() {
        ThemeLifecycleStateEntity e = new ThemeLifecycleStateEntity();
        e.setTradingDate(DATE);
        e.setThemeTag(THEME);
        e.setStage(ThemeLifecycleEngine.MAINSTREAM);
        return e;
    }

    protected List<ThemeReplayNodeEntity> fixtureNodes() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(FIXTURE)) {
            JsonNode root = objectMapper.readTree(in);
            List<ThemeReplayNodeEntity> nodes = new ArrayList<>();
            for (JsonNode n : StreamSupport.stream(root.path("candidates").spliterator(), false).toList()) {
                ThemeReplayNodeEntity node = new ThemeReplayNodeEntity();
                node.setTradingDate(DATE);
                node.setThemeTag(n.path("themeTag").asText());
                node.setSymbol(n.path("symbol").asText());
                node.setStockName(n.path("stockName").asText());
                boolean leader = n.path("isThemeLeader").asBoolean(false);
                node.setIsThemeLeader(leader);
                node.setLeadershipOnly(leader && !n.path("leaderTradable").asBoolean(false));
                node.setLeaderTradable(n.path("leaderTradable").asBoolean(false));
                node.setResearchRole(leader ? "THEME_LEADER" : "PEER_SHADOW");
                node.setCandidateRole(leader ? "THEME_LEADER" : "PEER_SHADOW");
                node.setThemeLeaderSymbol(n.path("themeLeaderSymbol").asText(leader ? node.getSymbol() : "2327"));
                node.setResearchUniverse(true);
                node.setTradableUniverse(false);
                node.setRiskRejected(leader);
                node.setRejectionReason(leader ? "chase-high avoided; leadership only" : "research only");
                node.setSafetyNote("replay-only analytics-only");
                node.setAiGovernanceSummary("governance annotated");
                if (n.hasNonNull("themeImportanceScore")) node.setThemeImportanceScore(new BigDecimal(n.path("themeImportanceScore").asText()));
                if (n.hasNonNull("shadowRankScore")) node.setShadowRankScore(new BigDecimal(n.path("shadowRankScore").asText()));
                nodes.add(node);
            }
            return nodes;
        }
    }

    protected List<ThemeReplayEdgeEntity> fixtureEdges() {
        return List.of("2492", "3026", "3090", "6173", "2375").stream().map(symbol -> {
            ThemeReplayEdgeEntity e = new ThemeReplayEdgeEntity();
            e.setTradingDate(DATE);
            e.setThemeTag(THEME);
            e.setFromSymbol("2327");
            e.setToSymbol(symbol);
            e.setEdgeType("LEADER_TO_PEER");
            e.setConfidence(new BigDecimal("0.8000"));
            return e;
        }).toList();
    }

    protected List<ResearchUniverseItemEntity> fixtureResearch() throws Exception {
        List<ResearchUniverseItemEntity> items = new ArrayList<>();
        for (ThemeReplayNodeEntity node : fixtureNodes()) {
            ResearchUniverseItemEntity item = new ResearchUniverseItemEntity();
            item.setTradingDate(DATE);
            item.setThemeTag(THEME);
            item.setSymbol(node.getSymbol());
            item.setStockName(node.getStockName());
            item.setResearchRole(node.getResearchRole());
            item.setSource("fixture");
            item.setResearchUniverse(true);
            item.setTradableUniverse(false);
            item.setPromotedToTradable(false);
            item.setLeadershipOnly(Boolean.TRUE.equals(node.getLeadershipOnly()));
            item.setLeaderTradable(Boolean.TRUE.equals(node.getLeaderTradable()));
            item.setGovernanceStatus("SHADOW_ONLY");
            items.add(item);
        }
        return items;
    }
}
