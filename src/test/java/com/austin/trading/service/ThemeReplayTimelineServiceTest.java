package com.austin.trading.service;

import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.ThemePeerShadowCandidateEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.ThemeLeaderRetentionRepository;
import com.austin.trading.repository.ThemeLeadershipSnapshotRepository;
import com.austin.trading.repository.ThemePeerShadowCandidateRepository;
import com.austin.trading.repository.ThemeReplayEdgeRepository;
import com.austin.trading.repository.ThemeReplayNodeRepository;
import com.austin.trading.repository.ThemeReplaySnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ThemeReplayTimelineServiceTest {

    private ThemeReplaySnapshotRepository snapshotRepository;
    private ThemeReplayNodeRepository nodeRepository;
    private ThemeReplayEdgeRepository edgeRepository;
    private ThemeLeadershipSnapshotRepository leadershipSnapshotRepository;
    private ThemeLeaderRetentionRepository leaderRetentionRepository;
    private ThemePeerShadowCandidateRepository peerShadowCandidateRepository;
    private CandidateStockRepository candidateStockRepository;
    private ThemeReplayTimelineService service;

    @BeforeEach
    void setUp() {
        snapshotRepository = mock(ThemeReplaySnapshotRepository.class);
        nodeRepository = mock(ThemeReplayNodeRepository.class);
        edgeRepository = mock(ThemeReplayEdgeRepository.class);
        leadershipSnapshotRepository = mock(ThemeLeadershipSnapshotRepository.class);
        leaderRetentionRepository = mock(ThemeLeaderRetentionRepository.class);
        peerShadowCandidateRepository = mock(ThemePeerShadowCandidateRepository.class);
        candidateStockRepository = mock(CandidateStockRepository.class);
        service = new ThemeReplayTimelineService(
                snapshotRepository,
                nodeRepository,
                edgeRepository,
                leadershipSnapshotRepository,
                leaderRetentionRepository,
                peerShadowCandidateRepository,
                candidateStockRepository,
                new ObjectMapper()
        );
    }

    @Test
    void buildTimelineCreatesReplayOnlyNodesEdgesAndDoesNotModifyProductionCandidates() {
        LocalDate date = LocalDate.of(2026, 5, 22);
        CandidateStockEntity leader = candidate(date, "2327", "國巨", "被動元件", "THEME_LEADER", true, false, "2327", "9.9", "1.8", "9.7");
        leader.setLeaderRetentionReason("leadership-only; near-limit-up/chase-high risk; not FinalDecision tradable candidate");
        CandidateStockEntity peer = candidate(date, "2492", "華新科", "被動元件", "PEER_SHADOW_CONTEXT", false, false, "2327", "8.9", "5.6", "8.7");
        when(candidateStockRepository.findByTradingDateOrderByScoreDesc(eq(date), any(Pageable.class)))
                .thenReturn(List.of(leader, peer));
        when(peerShadowCandidateRepository.findByTradingDateOrderBySourcePhaseAscLeaderSymbolAscShadowRankScoreDesc(date))
                .thenReturn(List.of(peerShadow(date, "2327", "2492", "華新科", "被動元件")));
        when(snapshotRepository.findByTradingDateOrderByThemeTagAsc(date)).thenReturn(List.of());
        when(nodeRepository.findByTradingDateAndThemeTagOrderByIdAsc(date, "被動元件")).thenReturn(List.of());
        when(edgeRepository.findByTradingDateAndThemeTagOrderByIdAsc(date, "被動元件")).thenReturn(List.of());

        var timeline = service.build(date);

        assertThat(timeline.shadowOnly()).isTrue();
        assertThat(timeline.replayOnly()).isTrue();
        assertThat(timeline.safetyBoundary().doesNotAffectFinalDecision()).isTrue();
        assertThat(timeline.safetyBoundary().doesNotAffectBuySellEnter()).isTrue();
        assertThat(timeline.safetyBoundary().researchUniverseNotTradable()).isTrue();
        assertThat(timeline.nodes()).extracting("symbol").contains("2327", "2492");
        assertThat(timeline.nodes()).filteredOn(n -> "2327".equals(n.symbol())).first().satisfies(n -> {
            assertThat(n.researchRole()).isEqualTo("THEME_LEADER");
            assertThat(n.leadershipOnly()).isTrue();
            assertThat(n.tradableUniverse()).isFalse();
            assertThat(n.leaderTradable()).isFalse();
            assertThat(n.riskRejected()).isTrue();
        });
        assertThat(timeline.nodes()).filteredOn(n -> "2492".equals(n.symbol())).first().satisfies(n -> {
            assertThat(n.researchRole()).isEqualTo("PEER_SHADOW");
            assertThat(n.researchUniverse()).isTrue();
            assertThat(n.tradableUniverse()).isFalse();
        });
        assertThat(timeline.edges()).anySatisfy(e -> {
            assertThat(e.fromSymbol()).isEqualTo("2327");
            assertThat(e.toSymbol()).isEqualTo("2492");
            assertThat(e.edgeType()).isEqualTo("LEADER_TO_PEER");
        });

        verify(candidateStockRepository, never()).save(any());
        verify(candidateStockRepository, never()).deleteByTradingDate(any());
        verify(snapshotRepository).deleteByTradingDate(date);
        verify(nodeRepository).deleteByTradingDate(date);
        verify(edgeRepository).deleteByTradingDate(date);
    }

    private CandidateStockEntity candidate(LocalDate date, String symbol, String name, String theme, String role,
                                           boolean isLeader, boolean leaderTradable, String leaderSymbol,
                                           String themeScore, String tradableScore, String shadowScore) {
        CandidateStockEntity entity = new CandidateStockEntity();
        entity.setTradingDate(date);
        entity.setSymbol(symbol);
        entity.setStockName(name);
        entity.setThemeTag(theme);
        entity.setCandidateRole(role);
        entity.setIsThemeLeader(isLeader);
        entity.setLeaderTradable(leaderTradable);
        entity.setThemeLeaderSymbol(leaderSymbol);
        entity.setThemeImportanceScore(new BigDecimal(themeScore));
        entity.setTradableScore(new BigDecimal(tradableScore));
        entity.setShadowRankScore(new BigDecimal(shadowScore));
        entity.setScore(new BigDecimal(shadowScore));
        return entity;
    }

    private ThemePeerShadowCandidateEntity peerShadow(LocalDate date, String leaderSymbol, String symbol, String name, String theme) {
        ThemePeerShadowCandidateEntity entity = new ThemePeerShadowCandidateEntity();
        entity.setTradingDate(date);
        entity.setSourcePhase("POSTMARKET");
        entity.setLeaderSymbol(leaderSymbol);
        entity.setSymbol(symbol);
        entity.setStockName(name);
        entity.setThemeTag(theme);
        entity.setCandidateRole("PEER_SHADOW_CONTEXT");
        entity.setTradable(false);
        entity.setShadowRankScore(new BigDecimal("8.7"));
        entity.setRejectionReason("peer shadow/replay context only");
        return entity;
    }
}
