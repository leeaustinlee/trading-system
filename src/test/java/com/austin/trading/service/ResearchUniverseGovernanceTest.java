package com.austin.trading.service;

import com.austin.trading.entity.ResearchUniverseItemEntity;
import com.austin.trading.entity.ThemeLeadershipSnapshotEntity;
import com.austin.trading.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ResearchUniverseGovernanceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 22);

    private ResearchUniverseItemRepository researchRepository;
    private ThemeLeadershipSnapshotRepository leadershipSnapshotRepository;
    private ThemeLeaderRetentionRepository leaderRetentionRepository;
    private ThemePeerShadowCandidateRepository peerShadowCandidateRepository;
    private ThemeReplayNodeRepository replayNodeRepository;
    private CandidateStockRepository candidateStockRepository;
    private ResearchUniverseService service;

    @BeforeEach
    void setUp() {
        researchRepository = mock(ResearchUniverseItemRepository.class);
        leadershipSnapshotRepository = mock(ThemeLeadershipSnapshotRepository.class);
        leaderRetentionRepository = mock(ThemeLeaderRetentionRepository.class);
        peerShadowCandidateRepository = mock(ThemePeerShadowCandidateRepository.class);
        replayNodeRepository = mock(ThemeReplayNodeRepository.class);
        candidateStockRepository = mock(CandidateStockRepository.class);
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
    void defaultsEveryResearchItemToShadowOnlyAndBlocksPromotionEvenWhenLeaderTradableIsFalse() {
        when(leadershipSnapshotRepository.findByTradingDateOrderByLeaderRankAsc(DATE)).thenReturn(List.of(leader(false)));
        when(leaderRetentionRepository.findByTargetPhaseAndActiveTrueAndTradingDateLessThanEqualOrderByTradingDateDescLeaderRankAsc("OPENING", DATE)).thenReturn(List.of());
        when(peerShadowCandidateRepository.findByTradingDateOrderBySourcePhaseAscLeaderSymbolAscShadowRankScoreDesc(DATE)).thenReturn(List.of());
        when(replayNodeRepository.findByTradingDateOrderByThemeTagAscSymbolAsc(DATE)).thenReturn(List.of());
        when(candidateStockRepository.findByTradingDateOrderByScoreDesc(eq(DATE), any())).thenReturn(List.of());

        var response = service.build(DATE);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0)).satisfies(i -> {
            assertThat(i.researchRole()).isEqualTo("LEADERSHIP_ONLY");
            assertThat(i.governanceStatus()).isEqualTo("SHADOW_ONLY");
            assertThat(i.researchUniverse()).isTrue();
            assertThat(i.tradableUniverse()).isFalse();
            assertThat(i.promotedToTradable()).isFalse();
            assertThat(i.promotionReason()).isNull();
            assertThat(i.leadershipOnly()).isTrue();
            assertThat(i.leaderTradable()).isFalse();
            assertThat(i.safetyBoundary().promotionReviewRequired()).isTrue();
            assertThat(i.safetyBoundary().doesNotAffectFinalDecision()).isTrue();
            assertThat(i.safetyBoundary().doesNotAffectBuySellEnter()).isTrue();
        });
    }

    @Test
    void governanceSummaryNeverReportsTradableOrPromotedItemsInMvp5b() {
        when(researchRepository.findByTradingDateOrderByThemeTagAscSymbolAscSourceAsc(DATE)).thenReturn(List.of(researchOnlyItem()));

        var summary = service.governanceSummary(DATE);

        assertThat(summary.totalCount()).isEqualTo(1);
        assertThat(summary.researchUniverseCount()).isEqualTo(1);
        assertThat(summary.tradableUniverseCount()).isZero();
        assertThat(summary.promotedToTradableCount()).isZero();
        assertThat(summary.governanceStatusCounts()).containsEntry("SHADOW_ONLY", 1L);
        assertThat(summary.safetyBoundary().researchUniverseNotTradable()).isTrue();
    }

    private ResearchUniverseItemEntity researchOnlyItem() {
        ResearchUniverseItemEntity e = new ResearchUniverseItemEntity();
        e.setTradingDate(DATE);
        e.setSymbol("2327");
        e.setStockName("國巨");
        e.setThemeTag("被動元件");
        e.setResearchRole("LEADERSHIP_ONLY");
        e.setSource("leadership");
        e.setGovernanceStatus("SHADOW_ONLY");
        e.setResearchUniverse(true);
        e.setTradableUniverse(false);
        e.setPromotedToTradable(false);
        e.setLeadershipOnly(true);
        e.setLeaderTradable(false);
        return e;
    }

    private ThemeLeadershipSnapshotEntity leader(boolean tradable) {
        ThemeLeadershipSnapshotEntity e = new ThemeLeadershipSnapshotEntity();
        e.setTradingDate(DATE);
        e.setSourcePhase("POSTMARKET");
        e.setSymbol("2327");
        e.setStockName("國巨");
        e.setThemeTag("被動元件");
        e.setLeaderRank(1);
        e.setScore(new BigDecimal("9.9"));
        e.setTradable(tradable);
        e.setTradableReason(tradable ? "still requires promotion review" : "leader_tradable=false; leadership-only");
        return e;
    }
}
