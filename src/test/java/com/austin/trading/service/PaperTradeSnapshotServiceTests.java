package com.austin.trading.service;

import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.entity.PaperTradeSnapshotEntity;
import com.austin.trading.repository.PaperTradeRepository;
import com.austin.trading.repository.PaperTradeSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaperTradeSnapshotServiceTests {

    private PaperTradeSnapshotRepository repo;
    private PaperTradeRepository paperTradeRepo;
    private ScoreConfigService config;
    private PaperTradeSnapshotService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repo = mock(PaperTradeSnapshotRepository.class);
        paperTradeRepo = mock(PaperTradeRepository.class);
        config = mock(ScoreConfigService.class);
        when(config.getBoolean(eq("trading.paper_mode.enabled"), anyBoolean())).thenReturn(true);

        ObjectProvider<ScoreConfigService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(config);

        service = new PaperTradeSnapshotService(repo, paperTradeRepo, new ObjectMapper(), provider);

        when(repo.save(any(PaperTradeSnapshotEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(repo.findTopByPaperTradeIdAndSnapshotTypeOrderByCapturedAtDesc(any(), anyString()))
                .thenReturn(Optional.empty());
    }

    private PaperTradeEntity buildTrade() {
        PaperTradeEntity t = new PaperTradeEntity();
        try {
            // ID via reflection — entity uses generated IDENTITY
            java.lang.reflect.Field f = PaperTradeEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(t, 42L);
        } catch (Exception ignore) {}
        t.setSymbol("2330");
        t.setStockName("台積電");
        t.setEntryDate(LocalDate.of(2026, 4, 28));
        t.setEntryPrice(new BigDecimal("590.00"));
        t.setIntendedEntryPrice(new BigDecimal("590.00"));
        t.setSimulatedEntryPrice(new BigDecimal("590.59"));
        t.setStopLossPrice(new BigDecimal("570.00"));
        t.setTarget1Price(new BigDecimal("615.00"));
        t.setTarget2Price(new BigDecimal("640.00"));
        t.setEntryGrade("B_TRIAL");
        t.setEntryRrRatio(new BigDecimal("2.50"));
        t.setEntryRegime("BULL_TREND");
        t.setFinalRankScore(new BigDecimal("6.80"));
        t.setThemeTag("半導體/IC");
        t.setThemeHeatScore(new BigDecimal("7.20"));
        t.setMaxHoldingDays(14);
        return t;
    }

    @Test
    void recordEntrySnapshot_writesOneRowWithRequiredKeys() throws Exception {
        PaperTradeEntity t = buildTrade();
        PaperTradeSnapshotEntity result = service.recordEntrySnapshot(t);

        ArgumentCaptor<PaperTradeSnapshotEntity> cap = ArgumentCaptor.forClass(PaperTradeSnapshotEntity.class);
        verify(repo, times(1)).save(cap.capture());
        PaperTradeSnapshotEntity saved = cap.getValue();
        assertThat(saved.getPaperTradeId()).isEqualTo(42L);
        assertThat(saved.getSnapshotType()).isEqualTo("ENTRY");
        assertThat(saved.getSchemaVersion()).isEqualTo("v1.0");
        JsonNode payload = new ObjectMapper().readTree(saved.getPayloadJson());
        assertThat(payload.get("symbol").asText()).isEqualTo("2330");
        assertThat(payload.get("entryGrade").asText()).isEqualTo("B_TRIAL");
        assertThat(payload.get("entryRegime").asText()).isEqualTo("BULL_TREND");
        assertThat(new BigDecimal(payload.get("intendedEntryPrice").asText())).isEqualByComparingTo("590.00");
        assertThat(new BigDecimal(payload.get("simulatedEntryPrice").asText())).isEqualByComparingTo("590.59");
        assertThat(payload.get("themeTag").asText()).isEqualTo("半導體/IC");
        assertThat(result).isNotNull();
    }

    @Test
    void recordEntrySnapshot_skipsIfPaperModeDisabled() {
        when(config.getBoolean(eq("trading.paper_mode.enabled"), anyBoolean())).thenReturn(false);
        PaperTradeSnapshotEntity result = service.recordEntrySnapshot(buildTrade());
        assertThat(result).isNull();
        verify(repo, never()).save(any(PaperTradeSnapshotEntity.class));
    }

    @Test
    void recordEntrySnapshot_idempotentWhenAlreadyExists() {
        PaperTradeSnapshotEntity existing = new PaperTradeSnapshotEntity(
                42L, "ENTRY", java.time.LocalDateTime.now(), "{}", "v1.0");
        when(repo.findTopByPaperTradeIdAndSnapshotTypeOrderByCapturedAtDesc(42L, "ENTRY"))
                .thenReturn(Optional.of(existing));

        PaperTradeSnapshotEntity result = service.recordEntrySnapshot(buildTrade());
        assertThat(result).isSameAs(existing);
        verify(repo, never()).save(any());
    }

    @Test
    void recordExitSnapshot_includesPnlAndReviewStatus() throws Exception {
        PaperTradeEntity t = buildTrade();
        t.setStatus("CLOSED");
        t.setExitDate(LocalDate.of(2026, 5, 2));
        t.setExitPrice(new BigDecimal("615.00"));
        t.setSimulatedExitPrice(new BigDecimal("614.39"));
        t.setExitReason("TP1_HIT");
        t.setHoldingDays(4);
        t.setPnlPct(new BigDecimal("4.13"));
        t.setPnlAmount(new BigDecimal("2435.00"));

        service.recordExitSnapshot(t, "EXIT", "TP1 reached");

        ArgumentCaptor<PaperTradeSnapshotEntity> cap = ArgumentCaptor.forClass(PaperTradeSnapshotEntity.class);
        verify(repo, times(1)).save(cap.capture());
        PaperTradeSnapshotEntity saved = cap.getValue();
        assertThat(saved.getSnapshotType()).isEqualTo("EXIT");
        JsonNode payload = new ObjectMapper().readTree(saved.getPayloadJson());
        assertThat(payload.get("exitReason").asText()).isEqualTo("TP1_HIT");
        assertThat(payload.get("holdingDays").asInt()).isEqualTo(4);
        assertThat(new BigDecimal(payload.get("pnlPct").asText())).isEqualByComparingTo("4.13");
        assertThat(payload.get("latestReviewStatus").asText()).isEqualTo("EXIT");
        assertThat(payload.get("latestReviewReason").asText()).isEqualTo("TP1 reached");
    }

    @Test
    void backfillMissingExitSnapshots_fillsOnlyClosedWithoutSnapshot() {
        PaperTradeEntity t1 = buildTrade();
        PaperTradeEntity t2 = buildTrade();
        try {
            java.lang.reflect.Field f = PaperTradeEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(t2, 43L);
        } catch (Exception ignore) {}
        when(paperTradeRepo.findByStatusOrderByEntryDateAscIdAsc("CLOSED"))
                .thenReturn(List.of(t1, t2));
        when(repo.countByPaperTradeIdAndSnapshotType(42L, "EXIT")).thenReturn(0L);
        when(repo.countByPaperTradeIdAndSnapshotType(43L, "EXIT")).thenReturn(1L);

        int filled = service.backfillMissingExitSnapshots();

        assertThat(filled).isEqualTo(1);
        verify(repo, times(1)).save(any(PaperTradeSnapshotEntity.class));
    }
}
