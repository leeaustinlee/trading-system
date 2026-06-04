package com.austin.trading.service;

import com.austin.trading.dto.response.PositionThesisLedgerResponse;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.entity.PositionThesisLedgerEntity;
import com.austin.trading.repository.DecisionSnapshotLedgerRepository;
import com.austin.trading.repository.FinalDecisionRepository;
import com.austin.trading.repository.PaperTradeRepository;
import com.austin.trading.repository.PositionRepository;
import com.austin.trading.repository.PositionThesisLedgerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PositionThesisLedgerServiceTest {
    private final PositionRepository positionRepository = mock(PositionRepository.class);
    private final PaperTradeRepository paperTradeRepository = mock(PaperTradeRepository.class);
    private final PositionThesisLedgerRepository thesisRepository = mock(PositionThesisLedgerRepository.class);
    private final FinalDecisionRepository finalDecisionRepository = mock(FinalDecisionRepository.class);
    private final DecisionSnapshotLedgerRepository snapshotRepository = mock(DecisionSnapshotLedgerRepository.class);
    private final PortfolioHealthV2Service healthV2Service = mock(PortfolioHealthV2Service.class);
    private final PositionThesisLedgerService service = new PositionThesisLedgerService(
            positionRepository, paperTradeRepository, thesisRepository, finalDecisionRepository,
            snapshotRepository, healthV2Service, new ObjectMapper());

    @Test
    void themeActiveStructureIntactRelativeStrengthStrongBecomesActive() {
        PositionEntity p = position("2330", "100", "95", "120");
        PaperTradeEntity trade = paperTrade("2330", "100", "AI主流股", "半導體", "AI伺服器", "突破整理區且法人買超");
        when(positionRepository.findByStatus("OPEN")).thenReturn(List.of(p));
        when(paperTradeRepository.findBySymbolAndStatusOrderByEntryDateAscIdAsc("2330", "OPEN")).thenReturn(List.of(trade));
        when(thesisRepository.findTopBySymbolAndOpenPositionTrueOrderByLatestReviewDateDescIdDesc("2330")).thenReturn(Optional.empty());
        when(thesisRepository.save(any(PositionThesisLedgerEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(healthV2Service.healthV2ReadOnlySummary()).thenReturn(health("2330", "105", "STRUCTURE_INTACT", "OUTPERFORM", "HOLD", true));

        PositionThesisLedgerResponse.Item item = service.refreshOpenTheses().items().get(0);

        assertThat(item.thesisStatus()).isEqualTo("ACTIVE");
        assertThat(item.primaryTheme()).isEqualTo("AI主流股");
        assertThat(item.thesisSummary()).contains("AI伺服器");
        assertThat(item.productionDecisionAllowed()).isFalse();
        assertThat(item.autoSellEnabled()).isFalse();
        assertThat(item.manualConfirmRequired()).isTrue();
    }

    @Test
    void themeFadingStructureBrokenRelativeWeakBecomesInvalidated() {
        PositionEntity p = position("2330", "100", "95", "120");
        when(positionRepository.findByStatus("OPEN")).thenReturn(List.of(p));
        when(paperTradeRepository.findBySymbolAndStatusOrderByEntryDateAscIdAsc("2330", "OPEN")).thenReturn(List.of(paperTrade("2330", "100", null, null, null, null)));
        when(thesisRepository.findTopBySymbolAndOpenPositionTrueOrderByLatestReviewDateDescIdDesc("2330")).thenReturn(Optional.empty());
        when(thesisRepository.save(any(PositionThesisLedgerEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(healthV2Service.healthV2ReadOnlySummary()).thenReturn(health("2330", "90", "PREVIOUS_LOW_BREAK", "UNDERPERFORM", "EXIT_REVIEW", false));

        PositionThesisLedgerResponse.Item item = service.refreshOpenTheses().items().get(0);

        assertThat(item.thesisStatus()).isEqualTo("INVALIDATED");
        assertThat(item.latestReviewReason()).contains("題材退潮");
    }

    @Test
    void insufficientDataBecomesUnknown() {
        PositionEntity p = position("2330", "100", "95", "120");
        when(positionRepository.findByStatus("OPEN")).thenReturn(List.of(p));
        when(paperTradeRepository.findBySymbolAndStatusOrderByEntryDateAscIdAsc("2330", "OPEN")).thenReturn(List.of());
        when(thesisRepository.findTopBySymbolAndOpenPositionTrueOrderByLatestReviewDateDescIdDesc("2330")).thenReturn(Optional.empty());
        when(thesisRepository.save(any(PositionThesisLedgerEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(healthV2Service.healthV2ReadOnlySummary()).thenReturn(Map.of("positions", List.of()));

        PositionThesisLedgerResponse.Item item = service.refreshOpenTheses().items().get(0);

        assertThat(item.thesisStatus()).isEqualTo("UNKNOWN");
        assertThat(item.latestReviewReason()).contains("資料不足");
    }

    @Test
    void profitableButWeakeningBecomesWeakening() {
        PositionEntity p = position("2330", "100", "95", "120");
        when(positionRepository.findByStatus("OPEN")).thenReturn(List.of(p));
        when(paperTradeRepository.findBySymbolAndStatusOrderByEntryDateAscIdAsc("2330", "OPEN")).thenReturn(List.of(paperTrade("2330", "100", "半導體", null, null, null)));
        when(thesisRepository.findTopBySymbolAndOpenPositionTrueOrderByLatestReviewDateDescIdDesc("2330")).thenReturn(Optional.empty());
        when(thesisRepository.save(any(PositionThesisLedgerEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(healthV2Service.healthV2ReadOnlySummary()).thenReturn(health("2330", "110", "MA5_BREAK", "UNDERPERFORM", "REDUCE_REVIEW", true));

        PositionThesisLedgerResponse.Item item = service.refreshOpenTheses().items().get(0);

        assertThat(item.thesisStatus()).isEqualTo("WEAKENING");
    }

    private static PositionEntity position(String symbol, String avgCost, String stop, String tp2) {
        PositionEntity p = new PositionEntity();
        p.setSymbol(symbol);
        p.setStockName(symbol + " Corp");
        p.setStatus("OPEN");
        p.setAvgCost(new BigDecimal(avgCost));
        p.setStopLossPrice(new BigDecimal(stop));
        p.setTakeProfit2(new BigDecimal(tp2));
        p.setOpenedAt(LocalDateTime.now().minusDays(2));
        return p;
    }

    private static PaperTradeEntity paperTrade(String symbol, String entryPrice, String themeTag, String strategyType,
                                               String thesis, String reason) {
        PaperTradeEntity t = new PaperTradeEntity();
        t.setSymbol(symbol);
        t.setStatus("OPEN");
        t.setEntryDate(LocalDate.now().minusDays(2));
        t.setEntryPrice(new BigDecimal(entryPrice));
        t.setThemeTag(themeTag);
        t.setStrategyType(strategyType);
        t.setSource("CODEX");
        t.setFinalDecisionId(42L);
        t.setMaxHoldingDays(8);
        t.setEntryPayloadJson("{\"thesisSummary\":\"" + (thesis == null ? "" : thesis) + "\",\"entryReason\":\"" + (reason == null ? "" : reason) + "\",\"secondaryThemes\":[\"AI\",\"法人\"]}");
        return t;
    }

    private static Map<String, Object> health(String symbol, String currentPrice, String structureStatus,
                                              String rsStatus, String actionTier, boolean mainstreamTheme) {
        return Map.of("positions", List.of(Map.of(
                "symbol", symbol,
                "currentPrice", new BigDecimal(currentPrice),
                "structureStatus", structureStatus,
                "relativeStrengthStatus", rsStatus,
                "actionTier", actionTier,
                "structuralSignals", structureStatus.contains("BREAK") ? List.of("structure_broken") : List.of("structure_intact"),
                "healthInputs", Map.of("mainstreamTheme", mainstreamTheme, "themeStage", mainstreamTheme ? "ACTIVE" : "DECAY"),
                "dataGaps", List.of()
        )));
    }
}
