package com.austin.trading.service;

import com.austin.trading.entity.*;
import com.austin.trading.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResearchUniverseServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 22);
    private static final String FIXTURE = "replay/theme-first/passive-components-2026-05-22.json";

    @Mock private ResearchUniverseItemRepository researchRepository;
    @Mock private ThemeLeadershipSnapshotRepository leadershipSnapshotRepository;
    @Mock private ThemeLeaderRetentionRepository leaderRetentionRepository;
    @Mock private ThemePeerShadowCandidateRepository peerShadowCandidateRepository;
    @Mock private ThemeReplayNodeRepository replayNodeRepository;
    @Mock private CandidateStockRepository candidateStockRepository;

    private ResearchUniverseService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new ResearchUniverseService(
                researchRepository,
                leadershipSnapshotRepository,
                leaderRetentionRepository,
                peerShadowCandidateRepository,
                replayNodeRepository,
                candidateStockRepository
        );
    }

    @Test
    void buildsPassiveComponentsResearchUniverseWithoutTradablePromotion() throws Exception {
        when(candidateStockRepository.findByTradingDateOrderByScoreDesc(eq(DATE), any(Pageable.class)))
                .thenReturn(fixtureRows());
        when(leadershipSnapshotRepository.findByTradingDateOrderByLeaderRankAsc(DATE)).thenReturn(List.of());
        when(leaderRetentionRepository.findByTargetPhaseAndActiveTrueAndTradingDateLessThanEqualOrderByTradingDateDescLeaderRankAsc("OPENING", DATE)).thenReturn(List.of());
        when(peerShadowCandidateRepository.findByTradingDateOrderBySourcePhaseAscLeaderSymbolAscShadowRankScoreDesc(DATE)).thenReturn(List.of());
        when(replayNodeRepository.findByTradingDateOrderByThemeTagAscSymbolAsc(DATE)).thenReturn(List.of());

        var response = service.build(DATE);

        assertThat(response.shadowOnly()).isTrue();
        assertThat(response.researchOnly()).isTrue();
        assertThat(response.safetyBoundary().researchUniverseNotTradable()).isTrue();
        assertThat(response.safetyBoundary().promotionReviewRequired()).isTrue();

        assertThat(response.items()).filteredOn(i -> "2327".equals(i.symbol())).first().satisfies(i -> {
            assertThat(i.stockName()).isEqualTo("國巨");
            assertThat(i.researchRole()).isEqualTo("LEADERSHIP_ONLY");
            assertThat(i.governanceStatus()).isEqualTo("SHADOW_ONLY");
            assertThat(i.researchUniverse()).isTrue();
            assertThat(i.tradableUniverse()).isFalse();
            assertThat(i.promotedToTradable()).isFalse();
            assertThat(i.leadershipOnly()).isTrue();
            assertThat(i.leaderTradable()).isFalse();
        });

        for (String peer : Set.of("2492", "3026", "3090", "6173", "2375")) {
            assertThat(response.items()).filteredOn(i -> peer.equals(i.symbol())).first().satisfies(i -> {
                assertThat(i.researchRole()).isEqualTo("PEER_SHADOW");
                assertThat(i.governanceStatus()).isEqualTo("SHADOW_ONLY");
                assertThat(i.researchUniverse()).isTrue();
                assertThat(i.tradableUniverse()).isFalse();
                assertThat(i.themeLeaderSymbol()).isEqualTo("2327");
            });
        }

        ArgumentCaptor<List<ResearchUniverseItemEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(researchRepository).deleteByTradingDate(DATE);
        verify(researchRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).allSatisfy(i -> {
            assertThat(i.getResearchUniverse()).isTrue();
            assertThat(i.getTradableUniverse()).isFalse();
            assertThat(i.getPromotedToTradable()).isFalse();
            assertThat(i.getGovernanceStatus()).isEqualTo("SHADOW_ONLY");
        });
    }

    @Test
    void aggregatesRetainedLeaderPeerShadowDivergenceAndTaxonomyGapIntoResearchUniverse() {
        when(leadershipSnapshotRepository.findByTradingDateOrderByLeaderRankAsc(DATE)).thenReturn(List.of(leadership("2327", false)));
        when(leaderRetentionRepository.findByTargetPhaseAndActiveTrueAndTradingDateLessThanEqualOrderByTradingDateDescLeaderRankAsc("OPENING", DATE)).thenReturn(List.of(retainedLeader("2327", false)));
        when(peerShadowCandidateRepository.findByTradingDateOrderBySourcePhaseAscLeaderSymbolAscShadowRankScoreDesc(DATE)).thenReturn(List.of(peer("2492")));
        when(replayNodeRepository.findByTradingDateOrderByThemeTagAscSymbolAsc(DATE)).thenReturn(List.of(replay("3026", "DIVERGENCE"), replay("3090", "TAXONOMY_GAP")));
        when(candidateStockRepository.findByTradingDateOrderByScoreDesc(eq(DATE), any(Pageable.class))).thenReturn(List.of());

        var response = service.build(DATE);

        assertThat(response.items()).extracting("researchRole")
                .contains("LEADERSHIP_ONLY", "PEER_SHADOW", "DIVERGENCE", "TAXONOMY_GAP");
        assertThat(response.items()).allSatisfy(i -> {
            assertThat(i.researchUniverse()).isTrue();
            assertThat(i.tradableUniverse()).isFalse();
            assertThat(i.governanceStatus()).isEqualTo("SHADOW_ONLY");
            assertThat(i.safetyBoundary().doesNotAffectFinalDecision()).isTrue();
            assertThat(i.safetyBoundary().doesNotAffectBuySellEnter()).isTrue();
        });
    }

    private List<CandidateStockEntity> fixtureRows() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(FIXTURE)) {
            assertThat(in).isNotNull();
            JsonNode root = objectMapper.readTree(in);
            return StreamSupport.stream(root.path("candidates").spliterator(), false)
                    .map(node -> toCandidate(root.path("tradingDate").asText(), node))
                    .toList();
        }
    }

    private CandidateStockEntity toCandidate(String tradingDate, JsonNode node) {
        CandidateStockEntity entity = new CandidateStockEntity();
        entity.setTradingDate(LocalDate.parse(tradingDate));
        entity.setSymbol(node.path("symbol").asText());
        entity.setStockName(node.path("stockName").asText());
        entity.setThemeTag(node.path("themeTag").asText());
        entity.setScore(node.path("score").decimalValue());
        entity.setCandidateRole(node.path("candidateRole").asText());
        entity.setThemeImportanceScore(node.path("themeImportanceScore").decimalValue());
        entity.setTradableScore(node.path("tradableScore").decimalValue());
        entity.setShadowRankScore(node.path("shadowRankScore").decimalValue());
        entity.setThemeLeaderSymbol(node.path("themeLeaderSymbol").asText());
        entity.setIsThemeLeader(node.path("isThemeLeader").asBoolean());
        entity.setLeaderTradable(node.path("leaderTradable").asBoolean());
        entity.setLeaderRetentionReason(node.path("rejectionReason").asText());
        entity.setPayloadJson(node.toString());
        return entity;
    }

    private ThemeLeadershipSnapshotEntity leadership(String symbol, boolean tradable) {
        ThemeLeadershipSnapshotEntity e = new ThemeLeadershipSnapshotEntity();
        e.setTradingDate(DATE);
        e.setSourcePhase("POSTMARKET");
        e.setSymbol(symbol);
        e.setStockName("國巨");
        e.setThemeTag("被動元件");
        e.setScore(new BigDecimal("9.9"));
        e.setTradable(tradable);
        e.setTradableReason("leadership-only research context");
        return e;
    }

    private ThemeLeaderRetentionEntity retainedLeader(String symbol, boolean leaderTradable) {
        ThemeLeaderRetentionEntity e = new ThemeLeaderRetentionEntity();
        e.setTradingDate(DATE);
        e.setSourcePhase("POSTMARKET");
        e.setTargetPhase("OPENING");
        e.setSymbol(symbol);
        e.setStockName("國巨");
        e.setThemeTag("被動元件");
        e.setScore(new BigDecimal("9.8"));
        e.setLeaderTradable(leaderTradable);
        e.setRetentionReason("retained leader; not FinalDecision tradable candidate");
        e.setUseFor("research_universe");
        e.setActive(true);
        return e;
    }

    private ThemePeerShadowCandidateEntity peer(String symbol) {
        ThemePeerShadowCandidateEntity e = new ThemePeerShadowCandidateEntity();
        e.setTradingDate(DATE);
        e.setSourcePhase("OPENING");
        e.setLeaderSymbol("2327");
        e.setSymbol(symbol);
        e.setStockName("華新科");
        e.setThemeTag("被動元件");
        e.setCandidateRole("PEER_SHADOW_CONTEXT");
        e.setShadowRankScore(new BigDecimal("8.7"));
        e.setThemeImportanceScore(new BigDecimal("8.9"));
        e.setTradableScore(new BigDecimal("5.6"));
        e.setTradable(false);
        return e;
    }

    private ThemeReplayNodeEntity replay(String symbol, String role) {
        ThemeReplayNodeEntity e = new ThemeReplayNodeEntity();
        e.setTradingDate(DATE);
        e.setThemeTag("被動元件");
        e.setSymbol(symbol);
        e.setStockName(symbol);
        e.setResearchRole(role);
        e.setCandidateRole(role);
        e.setThemeLeaderSymbol("2327");
        e.setResearchUniverse(true);
        e.setTradableUniverse(false);
        e.setLeaderTradable(false);
        e.setShadowRankScore(new BigDecimal("7.5"));
        return e;
    }
}
