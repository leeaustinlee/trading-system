package com.austin.trading.service;

import com.austin.trading.dto.response.AdaptiveExitReviewResponse;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.entity.PositionThesisLedgerEntity;
import com.austin.trading.entity.StopOutcomeLedgerEntity;
import com.austin.trading.repository.PositionRepository;
import com.austin.trading.repository.StopOutcomeLedgerRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdaptiveExitReviewServiceTest {

    private final PositionRepository positionRepository = mock(PositionRepository.class);
    private final PortfolioHealthV2Service healthV2Service = mock(PortfolioHealthV2Service.class);
    private final StopOutcomeLedgerRepository ledgerRepository = mock(StopOutcomeLedgerRepository.class);
    private final PositionThesisLedgerService thesisLedgerService = mock(PositionThesisLedgerService.class);
    private final AdaptiveExitReviewService service = new AdaptiveExitReviewService(
            positionRepository, healthV2Service, ledgerRepository, thesisLedgerService);

    @Test
    void stopHitStructureIntactWashoutEvidenceObserve1d() {
        PositionEntity p = position("2330", "100", "95");
        when(positionRepository.findByStatus("OPEN")).thenReturn(List.of(p));
        when(healthV2Service.healthV2ReadOnlySummary()).thenReturn(health("2330", "94", "STRUCTURE_INTACT",
                "NORMAL", "OUTPERFORM", "HOLD", true, Map.of("ma10", "93", "ma20", "90")));
        when(ledgerRepository.findBySymbolAndExitDateGreaterThanEqualOrderByExitDateDescIdDesc("2330", LocalDate.now().minusDays(90)))
                .thenReturn(List.of(ledger("2330", "WASHOUT_REVERSAL"), ledger("2330", "WASHOUT_REVERSAL")));

        AdaptiveExitReviewResponse.Item item = service.reviewOpenPositions().items().get(0);

        assertThat(item.recommendation()).isEqualTo(AdaptiveExitReviewResponse.Recommendation.OBSERVE_1D);
        assertThat(item.productionDecisionAllowed()).isFalse();
        assertThat(item.autoSellEnabled()).isFalse();
        assertThat(item.manualConfirmRequired()).isTrue();
    }

    @Test
    void stopHitStructureBrokenTrueBreakdownEvidenceHardExit() {
        PositionEntity p = position("2330", "100", "95");
        when(positionRepository.findByStatus("OPEN")).thenReturn(List.of(p));
        when(healthV2Service.healthV2ReadOnlySummary()).thenReturn(health("2330", "94", "PREVIOUS_LOW_BREAK",
                "VOLUME_BREAKDOWN", "UNDERPERFORM", "EXIT_REVIEW", false, Map.of("ma10", "98", "ma20", "99")));
        when(ledgerRepository.findBySymbolAndExitDateGreaterThanEqualOrderByExitDateDescIdDesc("2330", LocalDate.now().minusDays(90)))
                .thenReturn(List.of(ledger("2330", "TRUE_BREAKDOWN"), ledger("2330", "TRUE_BREAKDOWN")));

        AdaptiveExitReviewResponse.Item item = service.reviewOpenPositions().items().get(0);

        assertThat(item.recommendation()).isEqualTo(AdaptiveExitReviewResponse.Recommendation.HARD_EXIT);
    }

    @Test
    void mixedEvidenceExitReview() {
        PositionEntity p = position("2330", "100", "95");
        when(positionRepository.findByStatus("OPEN")).thenReturn(List.of(p));
        when(healthV2Service.healthV2ReadOnlySummary()).thenReturn(health("2330", "94", "MA5_BREAK",
                "NORMAL", "INLINE", "SOFT_WARNING", true, Map.of("ma10", "96", "ma20", "92")));
        when(ledgerRepository.findBySymbolAndExitDateGreaterThanEqualOrderByExitDateDescIdDesc("2330", LocalDate.now().minusDays(90)))
                .thenReturn(List.of(ledger("2330", "WASHOUT_REVERSAL"), ledger("2330", "TRUE_BREAKDOWN")));

        AdaptiveExitReviewResponse.Item item = service.reviewOpenPositions().items().get(0);

        assertThat(item.recommendation()).isEqualTo(AdaptiveExitReviewResponse.Recommendation.EXIT_REVIEW);
    }

    @Test
    void noStopHitHealthyPositionHold() {
        PositionEntity p = position("2330", "100", "95");
        when(positionRepository.findByStatus("OPEN")).thenReturn(List.of(p));
        when(healthV2Service.healthV2ReadOnlySummary()).thenReturn(health("2330", "102", "STRUCTURE_INTACT",
                "NORMAL", "OUTPERFORM", "HOLD", true, Map.of("ma10", "100", "ma20", "98")));
        when(ledgerRepository.findBySymbolAndExitDateGreaterThanEqualOrderByExitDateDescIdDesc("2330", LocalDate.now().minusDays(90)))
                .thenReturn(List.of());

        AdaptiveExitReviewResponse.Item item = service.reviewOpenPositions().items().get(0);

        assertThat(item.recommendation()).isEqualTo(AdaptiveExitReviewResponse.Recommendation.HOLD);
    }

    @Test
    void profitableButWeakeningReduceReview() {
        PositionEntity p = position("2330", "100", "95");
        when(positionRepository.findByStatus("OPEN")).thenReturn(List.of(p));
        when(healthV2Service.healthV2ReadOnlySummary()).thenReturn(health("2330", "108", "MA5_BREAK",
                "VOLUME_WEAKENING", "UNDERPERFORM", "REDUCE_REVIEW", true, Map.of("ma10", "106", "ma20", "100")));
        when(ledgerRepository.findBySymbolAndExitDateGreaterThanEqualOrderByExitDateDescIdDesc("2330", LocalDate.now().minusDays(90)))
                .thenReturn(List.of());

        AdaptiveExitReviewResponse.Item item = service.reviewOpenPositions().items().get(0);

        assertThat(item.recommendation()).isEqualTo(AdaptiveExitReviewResponse.Recommendation.REDUCE_REVIEW);
    }

    @Test
    void stopHitButThesisActiveDoesNotHardExit() {
        PositionEntity p = position("2330", "100", "95");
        when(positionRepository.findByStatus("OPEN")).thenReturn(List.of(p));
        when(healthV2Service.healthV2ReadOnlySummary()).thenReturn(health("2330", "94", "STRUCTURE_INTACT",
                "NORMAL", "OUTPERFORM", "EXIT_REVIEW", true, Map.of("ma10", "93", "ma20", "90")));
        when(ledgerRepository.findBySymbolAndExitDateGreaterThanEqualOrderByExitDateDescIdDesc("2330", LocalDate.now().minusDays(90)))
                .thenReturn(List.of(ledger("2330", "TRUE_BREAKDOWN")));
        when(thesisLedgerService.getOpenThesisBySymbol("2330")).thenReturn(Optional.of(thesis("2330", "ACTIVE", "AI伺服器", "跌破月線")));

        AdaptiveExitReviewResponse.Item item = service.reviewOpenPositions().items().get(0);

        assertThat(item.thesisStatus()).isEqualTo("ACTIVE");
        assertThat(item.thesisSummary()).contains("AI伺服器");
        assertThat(item.invalidationCondition()).isEqualTo("跌破月線");
        assertThat(item.recommendation()).isNotEqualTo(AdaptiveExitReviewResponse.Recommendation.HARD_EXIT);
    }

    @Test
    void invalidatedThesisPushesStopHitTowardHardExit() {
        PositionEntity p = position("2330", "100", "95");
        when(positionRepository.findByStatus("OPEN")).thenReturn(List.of(p));
        when(healthV2Service.healthV2ReadOnlySummary()).thenReturn(health("2330", "94", "STRUCTURE_INTACT",
                "NORMAL", "OUTPERFORM", "EXIT_REVIEW", true, Map.of("ma10", "93", "ma20", "90")));
        when(ledgerRepository.findBySymbolAndExitDateGreaterThanEqualOrderByExitDateDescIdDesc("2330", LocalDate.now().minusDays(90)))
                .thenReturn(List.of());
        when(thesisLedgerService.getOpenThesisBySymbol("2330")).thenReturn(Optional.of(thesis("2330", "INVALIDATED", "AI伺服器", "跌破月線")));

        AdaptiveExitReviewResponse.Item item = service.reviewOpenPositions().items().get(0);

        assertThat(item.thesisStatus()).isEqualTo("INVALIDATED");
        assertThat(item.recommendation()).isEqualTo(AdaptiveExitReviewResponse.Recommendation.HARD_EXIT);
    }

    @Test
    void activeNarrativeWashoutEvidenceObserve1d() {
        PositionEntity p = position("2330", "100", "95");
        when(positionRepository.findByStatus("OPEN")).thenReturn(List.of(p));
        when(healthV2Service.healthV2ReadOnlySummary()).thenReturn(health("2330", "94", "STRUCTURE_INTACT",
                "NORMAL", "OUTPERFORM", "EXIT_REVIEW", true, Map.of("ma10", "93", "ma20", "90")));
        when(ledgerRepository.findBySymbolAndExitDateGreaterThanEqualOrderByExitDateDescIdDesc("2330", LocalDate.now().minusDays(90)))
                .thenReturn(List.of(ledger("2330", "WASHOUT_REVERSAL")));
        PositionThesisLedgerEntity thesis = thesis("2330", "ACTIVE", "AI Server thesis", "跌破月線");
        thesis.setThemeLifecycle("MAINSTREAM");
        thesis.setNarrativeHeat(new BigDecimal("8.5"));
        thesis.setCrowdingRisk("MEDIUM");
        thesis.setWavePhase("MID_TREND_CONTINUATION");
        thesis.setRotationStrength(new BigDecimal("0.7"));
        thesis.setInstitutionalAlignment("STRONG");
        thesis.setSectorLeadership("STRONG");
        thesis.setThemeStillActive(true);
        when(thesisLedgerService.getOpenThesisBySymbol("2330")).thenReturn(Optional.of(thesis));

        AdaptiveExitReviewResponse.Item item = service.reviewOpenPositions().items().get(0);

        assertThat(item.recommendation()).isEqualTo(AdaptiveExitReviewResponse.Recommendation.OBSERVE_1D);
        assertThat(item.themeLifecycle()).isEqualTo("MAINSTREAM");
        assertThat(item.narrativeHeat()).isEqualByComparingTo("8.5");
        assertThat(item.wavePhase()).isEqualTo("MID_TREND_CONTINUATION");
        assertThat(item.crowdingRisk()).isEqualTo("MEDIUM");
        assertThat(item.productionDecisionAllowed()).isFalse();
        assertThat(item.autoBuyEnabled()).isFalse();
        assertThat(item.autoSellEnabled()).isFalse();
        assertThat(item.manualConfirmRequired()).isTrue();
    }

    @Test
    void midTrendSectorLeadershipStrongHolds() {
        PositionEntity p = position("2330", "100", "95");
        when(positionRepository.findByStatus("OPEN")).thenReturn(List.of(p));
        when(healthV2Service.healthV2ReadOnlySummary()).thenReturn(health("2330", "106", "STRUCTURE_INTACT",
                "NORMAL", "OUTPERFORM", "HOLD", true, Map.of("ma10", "103", "ma20", "98")));
        when(ledgerRepository.findBySymbolAndExitDateGreaterThanEqualOrderByExitDateDescIdDesc("2330", LocalDate.now().minusDays(90)))
                .thenReturn(List.of());
        PositionThesisLedgerEntity thesis = thesis("2330", "ACTIVE", "AI Server thesis", "跌破月線");
        thesis.setWavePhase("MID_TREND_CONTINUATION");
        thesis.setSectorLeadership("STRONG");
        when(thesisLedgerService.getOpenThesisBySymbol("2330")).thenReturn(Optional.of(thesis));

        AdaptiveExitReviewResponse.Item item = service.reviewOpenPositions().items().get(0);

        assertThat(item.recommendation()).isEqualTo(AdaptiveExitReviewResponse.Recommendation.HOLD);
    }

    @Test
    void lateExtensionCrowdingHighReduceReview() {
        PositionEntity p = position("2330", "100", "95");
        when(positionRepository.findByStatus("OPEN")).thenReturn(List.of(p));
        when(healthV2Service.healthV2ReadOnlySummary()).thenReturn(health("2330", "112", "STRUCTURE_INTACT",
                "NORMAL", "INLINE", "HOLD", true, Map.of("ma10", "108", "ma20", "100")));
        when(ledgerRepository.findBySymbolAndExitDateGreaterThanEqualOrderByExitDateDescIdDesc("2330", LocalDate.now().minusDays(90)))
                .thenReturn(List.of());
        PositionThesisLedgerEntity thesis = thesis("2330", "ACTIVE", "AI Server thesis", "跌破月線");
        thesis.setCrowdingRisk("HIGH");
        thesis.setNarrativeHeat(new BigDecimal("9.2"));
        thesis.setWavePhase("LATE_EXTENSION");
        when(thesisLedgerService.getOpenThesisBySymbol("2330")).thenReturn(Optional.of(thesis));

        AdaptiveExitReviewResponse.Item item = service.reviewOpenPositions().items().get(0);

        assertThat(item.recommendation()).isEqualTo(AdaptiveExitReviewResponse.Recommendation.REDUCE_REVIEW);
    }

    @Test
    void deadThemeBreakdownHardExit() {
        PositionEntity p = position("2330", "100", "95");
        when(positionRepository.findByStatus("OPEN")).thenReturn(List.of(p));
        when(healthV2Service.healthV2ReadOnlySummary()).thenReturn(health("2330", "94", "PREVIOUS_LOW_BREAK",
                "VOLUME_BREAKDOWN", "UNDERPERFORM", "EXIT_REVIEW", false, Map.of("ma10", "99", "ma20", "101")));
        when(ledgerRepository.findBySymbolAndExitDateGreaterThanEqualOrderByExitDateDescIdDesc("2330", LocalDate.now().minusDays(90)))
                .thenReturn(List.of(ledger("2330", "TRUE_BREAKDOWN")));
        PositionThesisLedgerEntity thesis = thesis("2330", "INVALIDATED", "AI Server thesis", "跌破月線");
        thesis.setThemeLifecycle("DEAD");
        thesis.setInstitutionalAlignment("NEGATIVE");
        when(thesisLedgerService.getOpenThesisBySymbol("2330")).thenReturn(Optional.of(thesis));

        AdaptiveExitReviewResponse.Item item = service.reviewOpenPositions().items().get(0);

        assertThat(item.recommendation()).isEqualTo(AdaptiveExitReviewResponse.Recommendation.HARD_EXIT);
    }

    @Test
    void missingNarrativeThemeDataKeepsUnknownAndExitReview() {
        PositionEntity p = position("2330", "100", "95");
        when(positionRepository.findByStatus("OPEN")).thenReturn(List.of(p));
        when(healthV2Service.healthV2ReadOnlySummary()).thenReturn(health("2330", "94", "MA5_BREAK",
                "NORMAL", "INLINE", "EXIT_REVIEW", false, Map.of("ma10", "96", "ma20", "99")));
        when(ledgerRepository.findBySymbolAndExitDateGreaterThanEqualOrderByExitDateDescIdDesc("2330", LocalDate.now().minusDays(90)))
                .thenReturn(List.of());

        AdaptiveExitReviewResponse.Item item = service.reviewOpenPositions().items().get(0);

        assertThat(item.themeLifecycle()).isEqualTo("UNKNOWN");
        assertThat(item.wavePhase()).isEqualTo("UNKNOWN");
        assertThat(item.recommendation()).isEqualTo(AdaptiveExitReviewResponse.Recommendation.EXIT_REVIEW);
    }

    private static PositionThesisLedgerEntity thesis(String symbol, String status, String summary, String invalidation) {
        PositionThesisLedgerEntity e = new PositionThesisLedgerEntity();
        e.setSymbol(symbol);
        e.setThesisStatus(status);
        e.setThesisSummary(summary);
        e.setInvalidationCondition(invalidation);
        return e;
    }

    private static PositionEntity position(String symbol, String avgCost, String stop) {
        PositionEntity p = new PositionEntity();
        p.setSymbol(symbol);
        p.setStockName(symbol + " Corp");
        p.setStatus("OPEN");
        p.setAvgCost(new BigDecimal(avgCost));
        p.setStopLossPrice(new BigDecimal(stop));
        p.setReviewStatus("HOLD");
        return p;
    }

    private static StopOutcomeLedgerEntity ledger(String symbol, String label) {
        StopOutcomeLedgerEntity e = new StopOutcomeLedgerEntity();
        e.setSymbol(symbol);
        e.setExitDate(LocalDate.now().minusDays(3));
        e.setExitReason("STOP_LOSS");
        e.setOutcomeLabel(label);
        e.setExitPrice(new BigDecimal("90"));
        return e;
    }

    private static Map<String, Object> health(String symbol, String currentPrice, String structureStatus,
                                              String volumeStatus, String rsStatus, String actionTier,
                                              boolean mainstreamTheme, Map<String, Object> technicals) {
        return Map.of(
                "status", "OK",
                "mode", "SHADOW_MANUAL_CONFIRM_ONLY",
                "autoSellEnabled", false,
                "positions", List.of(Map.of(
                        "symbol", symbol,
                        "currentPrice", new BigDecimal(currentPrice),
                        "structureStatus", structureStatus,
                        "volumeStatus", volumeStatus,
                        "relativeStrengthStatus", rsStatus,
                        "actionTier", actionTier,
                        "structuralSignals", signalsFor(structureStatus),
                        "healthInputs", Map.of("mainstreamTheme", mainstreamTheme, "themeStage", mainstreamTheme ? "ACTIVE" : "DECAY"),
                        "technicals", technicals,
                        "reasons", List.of("test")
                )));
    }

    private static List<String> signalsFor(String structureStatus) {
        if ("STRUCTURE_INTACT".equals(structureStatus)) return List.of("structure_intact");
        if (structureStatus != null && (structureStatus.contains("PREVIOUS_LOW_BREAK")
                || structureStatus.contains("MA10_BREAK")
                || structureStatus.contains("MA20_BREAK"))) return List.of("structure_broken");
        return List.of();
    }
}
