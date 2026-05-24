package com.austin.trading.service;

import com.austin.trading.entity.*;
import com.austin.trading.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PromotionReviewServiceTest {
    private static final LocalDate DATE = LocalDate.of(2026, 5, 22);

    private PromotionReviewItemRepository itemRepo;
    private PromotionReviewAuditRepository auditRepo;
    private ResearchUniverseItemRepository researchRepo;
    private HotGroupStockSignalRepository hotGroupRepo;
    private ThemeReplayNodeRepository replayNodeRepo;
    private ThemeLifecycleStateRepository lifecycleRepo;
    private ThemeReplayMetricsRepository metricsRepo;
    private CandidateStockRepository candidateStockRepo;
    private FinalDecisionRepository finalDecisionRepo;
    private PromotionReviewService service;
    private final List<PromotionReviewItemEntity> savedItems = new ArrayList<>();
    private final List<PromotionReviewAuditEntity> savedAudits = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        itemRepo = mock(PromotionReviewItemRepository.class);
        auditRepo = mock(PromotionReviewAuditRepository.class);
        researchRepo = mock(ResearchUniverseItemRepository.class);
        hotGroupRepo = mock(HotGroupStockSignalRepository.class);
        replayNodeRepo = mock(ThemeReplayNodeRepository.class);
        lifecycleRepo = mock(ThemeLifecycleStateRepository.class);
        metricsRepo = mock(ThemeReplayMetricsRepository.class);
        candidateStockRepo = mock(CandidateStockRepository.class);
        finalDecisionRepo = mock(FinalDecisionRepository.class);
        when(itemRepo.findByTradingDateOrderByThemeTagAscSymbolAscSourceAsc(DATE)).thenReturn(List.of());
        when(lifecycleRepo.findByTradingDateOrderByThemeTagAsc(DATE)).thenReturn(List.of(lifecycle("被動元件/MLCC", "MAINSTREAM"), lifecycle("被動元件/鋁電容", "EMERGING")));
        when(metricsRepo.findByTradingDateOrderByThemeTagAsc(DATE)).thenReturn(List.of(metrics("被動元件/MLCC"), metrics("被動元件/鋁電容")));
        when(itemRepo.save(any())).thenAnswer(inv -> {
            PromotionReviewItemEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(ids.getAndIncrement());
            savedItems.add(e);
            return e;
        });
        when(auditRepo.save(any())).thenAnswer(inv -> {
            PromotionReviewAuditEntity e = inv.getArgument(0);
            savedAudits.add(e);
            return e;
        });
        service = new PromotionReviewService(itemRepo, auditRepo, researchRepo, hotGroupRepo, replayNodeRepo,
                lifecycleRepo, metricsRepo, candidateStockRepo, finalDecisionRepo, new ObjectMapper());
    }

    @Test
    void buildReviewQueueCreatesPassiveComponentItemsWithoutCandidateOrFinalDecisionWrites() {
        when(researchRepo.findByTradingDateOrderByThemeTagAscSymbolAscSourceAsc(DATE)).thenReturn(List.of(research("2492", "華新科", "被動元件/MLCC", "PEER_SHADOW")));
        when(hotGroupRepo.findByTradingDateAndSourcePhaseOrderByRadarRankScoreDesc(DATE, "POSTMARKET")).thenReturn(List.of(
                hot("2327", "國巨", "被動元件/MLCC", "THEME_LEADER", true, new BigDecimal("30"), "REJECT_LIMIT_RISK"),
                hot("2492", "華新科", "被動元件/MLCC", "SECOND_LEADER", false, new BigDecimal("28"), "WATCH_ONLY"),
                hot("3090", "日電貿", "被動元件/通路代理", "CHANNEL_DISTRIBUTOR", false, new BigDecimal("24"), "WATCH_ONLY"),
                hot("2375", "凱美", "被動元件/鋁電容", "PEER", false, new BigDecimal("18"), "not_in_final_candidates_5"),
                hot("2472", "立隆電", "被動元件/鋁電容", "PEER", false, null, "insufficient_volume_evidence"),
                hot("6127", "九豪", "被動元件/材料設備", "PEER", false, null, "insufficient_volume_evidence")
        ));
        when(replayNodeRepo.findByTradingDateOrderByThemeTagAscSymbolAsc(DATE)).thenReturn(List.of());

        var response = service.build(DATE);

        assertThat(response.reviewOnly()).isTrue();
        assertThat(response.doesNotWriteCandidateStock()).isTrue();
        assertThat(response.doesNotAffectFinalDecision()).isTrue();
        assertThat(response.items()).extracting("symbol").contains("2327", "2492", "3090", "2375", "2472", "6127");
        assertThat(response.items()).filteredOn(i -> i.symbol().equals("2327"))
                .anySatisfy(i -> {
                    assertThat(i.source()).isEqualTo("RETAINED_LEADER");
                    assertThat(i.researchRole()).isEqualTo("LEADERSHIP_ONLY");
                    assertThat(i.riskBlocker()).isTrue();
                    assertThat(i.suggestedStatus()).isEqualTo("BLOCKED_BY_RISK");
                });
        assertThat(response.items()).filteredOn(i -> i.symbol().equals("2492"))
                .anySatisfy(i -> {
                    assertThat(i.source()).isEqualTo("PEER_SHADOW");
                    assertThat(i.suggestedStatus()).isEqualTo("CANDIDATE_POOL_SHADOW");
                    assertThat(i.tradable()).isFalse();
                    assertThat(i.notFinalDecisionEligible()).isTrue();
                });
        assertThat(response.items()).filteredOn(i -> i.symbol().equals("3090"))
                .anySatisfy(i -> assertThat(i.source()).isEqualTo("PEER_SHADOW"));
        assertThat(response.items()).filteredOn(i -> i.symbol().equals("2375"))
                .anySatisfy(i -> {
                    assertThat(i.source()).isEqualTo("EXPLAIN_MISS");
                    assertThat(i.suggestedStatus()).isEqualTo("WATCH_ONLY");
                });
        assertThat(response.items()).filteredOn(i -> i.symbol().equals("2472"))
                .anySatisfy(i -> assertThat(i.suggestedStatus()).isIn("NEED_MORE_EVIDENCE", "WATCH_ONLY"));
        assertThat(savedAudits).isNotEmpty();
        verify(candidateStockRepo, never()).save(any());
        verify(finalDecisionRepo, never()).save(any());
    }

    @Test
    void decisionWritesAuditAndKeepsCandidatePoolShadowNonTradable() {
        PromotionReviewItemEntity item = new PromotionReviewItemEntity();
        item.setId(42L);
        item.setTradingDate(DATE);
        item.setSymbol("2492");
        item.setStockName("華新科");
        item.setThemeTag("被動元件/MLCC");
        item.setResearchRole("PEER_SHADOW");
        item.setRadarScore(new BigDecimal("28"));
        item.setCurrentStatus("PENDING_REVIEW");
        item.setSource("PEER_SHADOW");
        item.setPayloadJson("{}");
        when(itemRepo.findById(42L)).thenReturn(Optional.of(item));

        var decided = service.decide(42L, new com.austin.trading.dto.request.PromotionReviewDecisionRequest(
                "CANDIDATE_POOL_SHADOW", "Austin", "research-worthy peer, still not tradable"));

        assertThat(decided.currentStatus()).isEqualTo("CANDIDATE_POOL_SHADOW");
        assertThat(decided.tradable()).isFalse();
        assertThat(decided.safetyBoundary().candidatePoolShadowIsNotTradable()).isTrue();
        assertThat(savedAudits).anySatisfy(a -> {
            assertThat(a.getAction()).isEqualTo("APPROVE_SHADOW");
            assertThat(a.getFromStatus()).isEqualTo("PENDING_REVIEW");
            assertThat(a.getToStatus()).isEqualTo("CANDIDATE_POOL_SHADOW");
        });
    }

    private ResearchUniverseItemEntity research(String symbol, String name, String theme, String source) {
        ResearchUniverseItemEntity e = new ResearchUniverseItemEntity();
        e.setTradingDate(DATE); e.setSymbol(symbol); e.setStockName(name); e.setThemeTag(theme); e.setSource(source);
        e.setResearchRole("PEER_SHADOW"); e.setThemeImportanceScore(new BigDecimal("8")); e.setTradableScore(BigDecimal.ZERO);
        e.setResearchUniverse(true); e.setTradableUniverse(false); e.setPromotedToTradable(false);
        return e;
    }

    private HotGroupStockSignalEntity hot(String symbol, String name, String theme, String role, boolean limitRisk, BigDecimal radar, String reason) {
        HotGroupStockSignalEntity e = new HotGroupStockSignalEntity();
        e.setTradingDate(DATE); e.setSourcePhase("POSTMARKET"); e.setSymbol(symbol); e.setStockName(name); e.setThemeTag(theme); e.setRole(role);
        e.setLimitRisk(limitRisk); e.setRadarRankScore(radar); e.setCandidateAction("WATCH_ONLY"); e.setRejectionReason(reason);
        return e;
    }

    private ThemeLifecycleStateEntity lifecycle(String theme, String stage) {
        ThemeLifecycleStateEntity e = new ThemeLifecycleStateEntity();
        e.setTradingDate(DATE); e.setThemeTag(theme); e.setStage(stage);
        return e;
    }

    private ThemeReplayMetricsEntity metrics(String theme) {
        ThemeReplayMetricsEntity e = new ThemeReplayMetricsEntity();
        e.setTradingDate(DATE); e.setThemeTag(theme); e.setLeaderRetentionRate(BigDecimal.ONE); e.setPeerDiscoveryHitRate(BigDecimal.ONE); e.setResearchUniverseCoverage(BigDecimal.ONE);
        return e;
    }
}
