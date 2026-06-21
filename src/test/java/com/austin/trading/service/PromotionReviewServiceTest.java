package com.austin.trading.service;

import com.austin.trading.entity.*;
import com.austin.trading.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private CandidateForwardTrackingRepository forwardTrackingRepo;
    private MarketIndexDailyRepository marketIndexRepo;
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
        forwardTrackingRepo = mock(CandidateForwardTrackingRepository.class);
        marketIndexRepo = mock(MarketIndexDailyRepository.class);
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
                lifecycleRepo, metricsRepo, candidateStockRepo, finalDecisionRepo, forwardTrackingRepo, marketIndexRepo, new ObjectMapper());
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
    void rebuildPreservesManualWatchOnlyItemAndMergesEvidenceWithoutOverwritingDecision() {
        PromotionReviewItemEntity manual = manualItem("2492", "華新科", "被動元件/MLCC", "PEER_SHADOW", "WATCH_ONLY");
        manual.setRadarScore(new BigDecimal("3"));
        manual.setPayloadJson("{\"manual\":true}");
        ResearchUniverseItemEntity refreshed = research("2492", "華新科", "被動元件/MLCC", "PEER_SHADOW");
        refreshed.setThemeImportanceScore(new BigDecimal("9"));
        when(itemRepo.findManualItemsByDate(DATE)).thenReturn(List.of(manual));
        when(itemRepo.findByTradingDateOrderByThemeTagAscSymbolAscSourceAsc(DATE)).thenReturn(List.of(manual));
        when(itemRepo.countManualItemsByDate(DATE)).thenReturn(1L);
        when(auditRepo.countManualAuditsByDate(DATE)).thenReturn(1L);
        when(itemRepo.deleteSystemGeneratedByDate(DATE)).thenReturn(2);
        when(auditRepo.deleteSystemBuildAuditsByDate(DATE)).thenReturn(3);
        when(researchRepo.findByTradingDateOrderByThemeTagAscSymbolAscSourceAsc(DATE)).thenReturn(List.of(refreshed));
        when(hotGroupRepo.findByTradingDateAndSourcePhaseOrderByRadarRankScoreDesc(DATE, "POSTMARKET")).thenReturn(List.of());
        when(replayNodeRepo.findByTradingDateOrderByThemeTagAscSymbolAsc(DATE)).thenReturn(List.of());

        var response = service.rebuild(DATE);

        assertThat(response.manualReviewPreserved()).isTrue();
        assertThat(response.preservedManualCount()).isEqualTo(1);
        assertThat(response.mergedManualCount()).isEqualTo(1);
        assertThat(response.deletedSystemCount()).isEqualTo(2);
        assertThat(response.items()).hasSize(1);
        var item = response.items().get(0);
        assertThat(item.currentStatus()).isEqualTo("WATCH_ONLY");
        assertThat(item.reviewer()).isEqualTo("manual-preservation-test");
        assertThat(item.decisionReason()).isEqualTo("manual decision must survive rebuild");
        assertThat(item.themeImportanceScore()).isEqualByComparingTo("9");
        assertThat(item.payloadJson()).contains("manualPreserved", "mergedEvidencePayload");
        assertThat(savedAudits).anySatisfy(a -> {
            assertThat(a.getAction()).isEqualTo("MERGE_EVIDENCE");
            assertThat(a.getActor()).isEqualTo("system/build");
        });
        verify(auditRepo).deleteSystemBuildAuditsByDate(DATE);
        verify(itemRepo).deleteSystemGeneratedByDate(DATE);
        verify(auditRepo, never()).deleteByTradingDate(DATE);
        verify(itemRepo, never()).deleteByTradingDate(DATE);
    }

    @Test
    void rebuildPreservesCandidatePoolShadowDecisionAndManualAudit() {
        PromotionReviewItemEntity manual = manualItem("2327", "國巨", "被動元件/MLCC", "RETAINED_LEADER", "CANDIDATE_POOL_SHADOW");
        manual.setReviewer(null);
        manual.setDecisionReason(null);
        manual.setReviewedAt(null);
        when(itemRepo.findManualItemsByDate(DATE)).thenReturn(List.of(manual));
        when(itemRepo.findByTradingDateOrderByThemeTagAscSymbolAscSourceAsc(DATE)).thenReturn(List.of(manual));
        when(itemRepo.countManualItemsByDate(DATE)).thenReturn(1L);
        when(auditRepo.countManualAuditsByDate(DATE)).thenReturn(1L);
        when(itemRepo.deleteSystemGeneratedByDate(DATE)).thenReturn(4);
        when(auditRepo.deleteSystemBuildAuditsByDate(DATE)).thenReturn(5);
        when(researchRepo.findByTradingDateOrderByThemeTagAscSymbolAscSourceAsc(DATE)).thenReturn(List.of());
        when(hotGroupRepo.findByTradingDateAndSourcePhaseOrderByRadarRankScoreDesc(DATE, "POSTMARKET")).thenReturn(List.of(
                hot("2327", "國巨", "被動元件/MLCC", "THEME_LEADER", true, new BigDecimal("30"), "REJECT_LIMIT_RISK")
        ));
        when(replayNodeRepo.findByTradingDateOrderByThemeTagAscSymbolAsc(DATE)).thenReturn(List.of());

        var response = service.rebuild(DATE);

        assertThat(response.manualReviewPreserved()).isTrue();
        assertThat(response.preservedManualCount()).isEqualTo(1);
        assertThat(response.mergedManualCount()).isEqualTo(1);
        assertThat(response.items()).filteredOn(i -> i.symbol().equals("2327") && i.source().equals("RETAINED_LEADER"))
                .singleElement().satisfies(i -> {
                    assertThat(i.currentStatus()).isEqualTo("CANDIDATE_POOL_SHADOW");
                    assertThat(i.tradable()).isFalse();
                    assertThat(i.notFinalDecisionEligible()).isTrue();
                });
        verify(auditRepo).deleteSystemBuildAuditsByDate(DATE);
        verify(itemRepo).deleteSystemGeneratedByDate(DATE);
    }

    @Test
    void rebuildDeletesOnlySystemGeneratedRowsAndBuildAudits() {
        when(itemRepo.findManualItemsByDate(DATE)).thenReturn(List.of());
        when(itemRepo.findByTradingDateOrderByThemeTagAscSymbolAscSourceAsc(DATE)).thenReturn(List.of());
        when(itemRepo.countManualItemsByDate(DATE)).thenReturn(0L);
        when(auditRepo.countManualAuditsByDate(DATE)).thenReturn(2L);
        when(itemRepo.deleteSystemGeneratedByDate(DATE)).thenReturn(7);
        when(auditRepo.deleteSystemBuildAuditsByDate(DATE)).thenReturn(9);
        when(researchRepo.findByTradingDateOrderByThemeTagAscSymbolAscSourceAsc(DATE)).thenReturn(List.of());
        when(hotGroupRepo.findByTradingDateAndSourcePhaseOrderByRadarRankScoreDesc(DATE, "POSTMARKET")).thenReturn(List.of());
        when(replayNodeRepo.findByTradingDateOrderByThemeTagAscSymbolAsc(DATE)).thenReturn(List.of());

        var response = service.rebuild(DATE);

        assertThat(response.preservedManualCount()).isZero();
        assertThat(response.mergedManualCount()).isZero();
        assertThat(response.deletedSystemCount()).isEqualTo(7);
        verify(auditRepo).deleteSystemBuildAuditsByDate(DATE);
        verify(itemRepo).deleteSystemGeneratedByDate(DATE);
        verify(auditRepo, never()).deleteByTradingDate(DATE);
        verify(itemRepo, never()).deleteByTradingDate(DATE);
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

    @Test
    void policySimulationJoinsForwardTrackingAndSummarizesMatchedRiskAndDataGaps() {
        LocalDate endDate = DATE.plusDays(1);
        PromotionReviewItemEntity matched = simulationItem(1L, DATE, "2492", false, false, new BigDecimal("8"));
        PromotionReviewItemEntity risk = simulationItem(2L, DATE, "2327", true, false, new BigDecimal("9"));
        PromotionReviewItemEntity missing = simulationItem(3L, endDate, "2375", false, false, null);
        when(itemRepo.findByTradingDateBetweenAndCurrentStatusOrderByTradingDateAscThemeTagAscSymbolAscSourceAsc(
                DATE, endDate, "CANDIDATE_POOL_SHADOW")).thenReturn(List.of(matched, risk, missing));
        when(forwardTrackingRepo.findByTradingDateBetween(DATE, endDate)).thenReturn(List.of(
                forward(DATE, "2492", "1.5", "3.0", "4.0", "-2.0", false),
                forward(DATE, "2327", "-1.0", "-2.0", "1.0", "-5.0", true)
        ));

        var response = service.policySimulation(DATE, endDate, null);

        assertThat(response.simulationOnly()).isTrue();
        assertThat(response.reviewOnly()).isTrue();
        assertThat(response.doesNotAffectFinalDecision()).isTrue();
        assertThat(response.boundedSoftBoostShadowOnly()).isTrue();
        assertThat(response.summary().itemCount()).isEqualTo(3);
        assertThat(response.summary().matchedForwardCount()).isEqualTo(2);
        assertThat(response.summary().dataGapCount()).isEqualTo(1);
        assertThat(response.summary().avgT1()).isEqualByComparingTo("0.2500");
        assertThat(response.summary().avgT5()).isEqualByComparingTo("0.5000");
        assertThat(response.summary().avgT10()).isEqualByComparingTo("2.5000");
        assertThat(response.summary().winRateT5()).isEqualByComparingTo("0.5000");
        assertThat(response.summary().hitStopCount()).isEqualTo(1);
        assertThat(response.summary().maxDrawdownAvg()).isEqualByComparingTo("-3.5000");
        assertThat(response.summary().blockedByRiskCount()).isEqualTo(1);
        assertThat(response.items()).filteredOn(i -> i.symbol().equals("2492")).singleElement()
                .satisfies(i -> {
                    assertThat(i.suggestedPolicy()).isEqualTo("ELIGIBLE_FOR_SOFT_BOOST_SHADOW");
                    assertThat(i.t5ReturnPct()).isEqualByComparingTo("3.0");
                    assertThat(i.dataGapReason()).isNull();
                });
        assertThat(response.items()).filteredOn(i -> i.symbol().equals("2327")).singleElement()
                .satisfies(i -> assertThat(i.suggestedPolicy()).isEqualTo("BLOCKED_BY_RISK"));
        assertThat(response.items()).filteredOn(i -> i.symbol().equals("2375")).singleElement()
                .satisfies(i -> {
                    assertThat(i.suggestedPolicy()).isEqualTo("NEED_MORE_EVIDENCE");
                    assertThat(i.dataGapReason()).isEqualTo("MISSING_FORWARD_TRACKING");
                });
        verify(itemRepo, never()).save(any());
        verify(auditRepo, never()).save(any());
        verify(candidateStockRepo, never()).save(any());
        verify(finalDecisionRepo, never()).save(any());
    }

    @Test
    void validationReportBlocksByDataGapUntilForwardEvidenceCompletes() {
        LocalDate endDate = DATE.plusDays(1);
        PromotionReviewItemEntity item = simulationItem(1L, DATE, "2492", false, false, new BigDecimal("8"));
        when(itemRepo.findByTradingDateBetweenAndCurrentStatusOrderByTradingDateAscThemeTagAscSymbolAscSourceAsc(
                DATE, endDate, "CANDIDATE_POOL_SHADOW")).thenReturn(List.of(item));
        CandidateForwardTrackingEntity forward = new CandidateForwardTrackingEntity();
        forward.setTradingDate(DATE);
        forward.setStockId("2492");
        forward.setEntryPriceAtDecision(BigDecimal.ONE);
        when(forwardTrackingRepo.findByTradingDateBetween(DATE, endDate)).thenReturn(List.of(forward));

        var response = service.validationReport(DATE, endDate, null);

        assertThat(response.validationOnly()).isTrue();
        assertThat(response.doesNotAffectFinalDecision()).isTrue();
        assertThat(response.noAutoPromotion()).isTrue();
        assertThat(response.softBoostShadowOnly()).isTrue();
        assertThat(response.graduationCriteria().minSample()).isEqualTo(10);
        assertThat(response.summary().overallStatus()).isEqualTo("BLOCKED_BY_DATA_GAP");
        assertThat(response.summary().dataGapCount()).isEqualTo(1);
        assertThat(response.items()).singleElement().satisfies(i -> {
            assertThat(i.validationStatus()).isEqualTo("BLOCKED_BY_DATA_GAP");
            assertThat(i.dataGapReason()).isEqualTo("PENDING_FORWARD_RETURN_BACKFILL");
        });
        verify(itemRepo, never()).save(any());
        verify(candidateStockRepo, never()).save(any());
        verify(finalDecisionRepo, never()).save(any());
    }

    @Test
    void validationReportAggregatesEligibleShadowEvidenceButRequiresMinimumSampleForOverallGraduation() {
        LocalDate endDate = DATE.plusDays(1);
        PromotionReviewItemEntity winner = simulationItem(1L, DATE, "2492", false, false, new BigDecimal("8"));
        PromotionReviewItemEntity loser = simulationItem(2L, DATE, "2327", false, false, new BigDecimal("8"));
        when(itemRepo.findByTradingDateBetweenAndCurrentStatusOrderByTradingDateAscThemeTagAscSymbolAscSourceAsc(
                DATE, endDate, "CANDIDATE_POOL_SHADOW")).thenReturn(List.of(winner, loser));
        when(forwardTrackingRepo.findByTradingDateBetween(DATE, endDate)).thenReturn(List.of(
                forward(DATE, "2492", "1", "2.5", "3", "-2", false),
                forward(DATE, "2327", "-1", "-0.5", "1", "-3", false)
        ));

        var response = service.validationReport(DATE, endDate, "CANDIDATE_POOL_SHADOW");

        assertThat(response.summary().itemCount()).isEqualTo(2);
        assertThat(response.summary().evidenceReadyCount()).isEqualTo(2);
        assertThat(response.summary().winRateT5()).isEqualByComparingTo("0.5000");
        assertThat(response.summary().overallStatus()).isEqualTo("NEED_MORE_EVIDENCE");
        assertThat(response.items()).filteredOn(i -> i.symbol().equals("2492")).singleElement()
                .satisfies(i -> assertThat(i.validationStatus()).isEqualTo("ELIGIBLE_FOR_SOFT_BOOST_SHADOW"));
        assertThat(response.items()).filteredOn(i -> i.symbol().equals("2327")).singleElement()
                .satisfies(i -> assertThat(i.validationStatus()).isEqualTo("KEEP_WATCHING"));
        verify(candidateStockRepo, never()).save(any());
        verify(finalDecisionRepo, never()).save(any());
    }

    @Test
    void bridgeForwardTrackingCreatesPromotionShadowRowsWithoutTradingWrites() {
        PromotionReviewItemEntity item = simulationItem(42L, DATE, "2492", false, false, new BigDecimal("8"));
        item.setStockName("華新科");
        item.setLifecycleStage("MAINSTREAM");
        item.setReviewReason("manual shadow approval");
        when(itemRepo.findByTradingDateBetweenAndCurrentStatusOrderByTradingDateAscThemeTagAscSymbolAscSourceAsc(
                DATE, DATE, "CANDIDATE_POOL_SHADOW")).thenReturn(List.of(item));
        when(forwardTrackingRepo.findByTradingDateAndStockIdAndFinalDecision(
                DATE, "2492", "PROMOTION_CANDIDATE_POOL_SHADOW")).thenReturn(Optional.empty());
        MarketIndexDailyEntity bar = new MarketIndexDailyEntity();
        bar.setClosePrice(new BigDecimal("100"));
        when(marketIndexRepo.findBySymbolAndTradingDate("2492", DATE)).thenReturn(Optional.of(bar));

        var response = service.bridgeForwardTracking(DATE, DATE, null);

        assertThat(response).containsEntry("trackingBridgeOnly", true)
                .containsEntry("doesNotAffectFinalDecision", true)
                .containsEntry("doesNotAffectBuySellEnter", true)
                .containsEntry("doesNotWriteCandidateStock", true)
                .containsEntry("doesNotWriteProductionScore", true)
                .containsEntry("noAutoPromotion", true)
                .containsEntry("written", 1)
                .containsEntry("sourceItems", 1)
                .containsEntry("returnBackfillRequired", true);
        verify(forwardTrackingRepo).save(argThat(row ->
                row.getTradingDate().equals(DATE)
                        && row.getStockId().equals("2492")
                        && row.getStockName().equals("華新科")
                        && row.getFinalDecision().equals("PROMOTION_CANDIDATE_POOL_SHADOW")
                        && row.getEntryPriceAtDecision().compareTo(new BigDecimal("100")) == 0
                        && row.getPrimaryStrategy().equals("PROMOTION_REVIEW")
                        && row.getGateName().equals("ELIGIBLE_FOR_SOFT_BOOST_SHADOW")
                        && row.getThemeTag().equals("被動元件/MLCC")
                        && row.getThemeReason().equals("manual shadow approval")
                        && row.getSourceCandidateId().equals(42L)));
        verify(candidateStockRepo, never()).save(any());
        verify(finalDecisionRepo, never()).save(any());
        verify(auditRepo, never()).save(any());
    }

    @Test
    void bridgeForwardTrackingSkipsExistingRows() {
        PromotionReviewItemEntity item = simulationItem(42L, DATE, "2492", false, false, new BigDecimal("8"));
        when(itemRepo.findByTradingDateBetweenAndCurrentStatusOrderByTradingDateAscThemeTagAscSymbolAscSourceAsc(
                DATE, DATE, "CANDIDATE_POOL_SHADOW")).thenReturn(List.of(item));
        when(forwardTrackingRepo.findByTradingDateAndStockIdAndFinalDecision(
                DATE, "2492", "PROMOTION_CANDIDATE_POOL_SHADOW")).thenReturn(Optional.of(new CandidateForwardTrackingEntity()));

        var response = service.bridgeForwardTracking(DATE, DATE, "CANDIDATE_POOL_SHADOW");

        assertThat(response).containsEntry("written", 0).containsEntry("skippedExisting", 1);
        verify(forwardTrackingRepo, never()).save(any());
        verify(candidateStockRepo, never()).save(any());
        verify(finalDecisionRepo, never()).save(any());
    }

    private PromotionReviewItemEntity manualItem(String symbol, String name, String theme, String source, String status) {
        PromotionReviewItemEntity e = new PromotionReviewItemEntity();
        e.setId(ids.getAndIncrement());
        e.setTradingDate(DATE);
        e.setSymbol(symbol);
        e.setStockName(name);
        e.setThemeTag(theme);
        e.setSource(source);
        e.setResearchRole("PEER_SHADOW");
        e.setCurrentStatus(status);
        e.setReviewer("manual-preservation-test");
        e.setReviewedAt(LocalDateTime.of(2026, 5, 22, 12, 30));
        e.setDecisionReason("manual decision must survive rebuild");
        e.setPayloadJson("{\"manual\":true}");
        return e;
    }

    private ResearchUniverseItemEntity research(String symbol, String name, String theme, String source) {
        ResearchUniverseItemEntity e = new ResearchUniverseItemEntity();
        e.setTradingDate(DATE); e.setSymbol(symbol); e.setStockName(name); e.setThemeTag(theme); e.setSource(source);
        e.setResearchRole("PEER_SHADOW"); e.setThemeImportanceScore(new BigDecimal("8")); e.setTradableScore(BigDecimal.ZERO);
        e.setResearchUniverse(true); e.setTradableUniverse(false); e.setPromotedToTradable(false);
        return e;
    }

    private PromotionReviewItemEntity simulationItem(Long id, LocalDate date, String symbol, boolean risk, boolean governance, BigDecimal evidenceScore) {
        PromotionReviewItemEntity e = new PromotionReviewItemEntity();
        e.setId(id);
        e.setTradingDate(date);
        e.setSymbol(symbol);
        e.setStockName(symbol);
        e.setThemeTag("被動元件/MLCC");
        e.setSource("PEER_SHADOW");
        e.setCurrentStatus("CANDIDATE_POOL_SHADOW");
        e.setRiskBlocker(risk);
        e.setGovernanceBlocker(governance);
        e.setEvidenceScore(evidenceScore);
        return e;
    }

    private CandidateForwardTrackingEntity forward(LocalDate date, String symbol, String t1, String t5, String t10, String maxDrawdown, boolean hitStop) {
        CandidateForwardTrackingEntity e = new CandidateForwardTrackingEntity();
        e.setTradingDate(date);
        e.setStockId(symbol);
        e.setEntryPriceAtDecision(BigDecimal.ONE);
        e.setT1CloseReturnPct(new BigDecimal(t1));
        e.setT5CloseReturnPct(new BigDecimal(t5));
        e.setT10CloseReturnPct(new BigDecimal(t10));
        e.setMaxDrawdownPct(new BigDecimal(maxDrawdown));
        e.setHitStop(hitStop);
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
