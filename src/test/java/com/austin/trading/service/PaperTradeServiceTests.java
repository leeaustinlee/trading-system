package com.austin.trading.service;

import com.austin.trading.client.TwseMisClient;
import com.austin.trading.engine.exit.FixedRuleExitEvaluator;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.repository.PaperTradeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * P0.2 paper_trade pipeline 測試:
 * <ul>
 *   <li>{@code recordEntry} 寫入 OPEN row 並正確 capture 所有欄位</li>
 *   <li>同 symbol + entry_date 已 OPEN 時 idempotent skip</li>
 *   <li>{@code trading.paper_mode.enabled=false} 時不寫入</li>
 * </ul>
 */
class PaperTradeServiceTests {

    private PaperTradeRepository repository;
    private TwseMisClient twseClient;
    private FixedRuleExitEvaluator exitEvaluator;
    private ObjectMapper objectMapper;
    private ScoreConfigService scoreConfig;
    @SuppressWarnings("unchecked")
    private ObjectProvider<ScoreConfigService> scoreConfigProvider;
    @SuppressWarnings("unchecked")
    private ObjectProvider<MarketRegimeService> marketRegimeProvider;

    private PaperTradeService service;

    @BeforeEach
    void setUp() {
        repository = mock(PaperTradeRepository.class);
        twseClient = mock(TwseMisClient.class);
        exitEvaluator = mock(FixedRuleExitEvaluator.class);
        objectMapper = new ObjectMapper();
        scoreConfig = mock(ScoreConfigService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ScoreConfigService> p = mock(ObjectProvider.class);
        scoreConfigProvider = p;
        when(scoreConfigProvider.getIfAvailable()).thenReturn(scoreConfig);
        @SuppressWarnings("unchecked")
        ObjectProvider<MarketRegimeService> rp = mock(ObjectProvider.class);
        marketRegimeProvider = rp;
        when(marketRegimeProvider.getIfAvailable()).thenReturn(null); // no regime in tests

        // default: feature flag ON
        when(scoreConfig.getBoolean(eq("trading.paper_mode.enabled"), anyBoolean())).thenReturn(true);

        // default: no existing rows
        when(repository.findByEntryDateAndSymbol(any(LocalDate.class), anyString()))
                .thenReturn(new ArrayList<>());
        // save returns the input
        when(repository.save(any(PaperTradeEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // default: no live quote (so simulated_entry_price falls back to intended)
        when(twseClient.getTseQuote(anyString())).thenReturn(java.util.Optional.empty());
        when(twseClient.getOtcQuote(anyString())).thenReturn(java.util.Optional.empty());

        service = new PaperTradeService(
                repository, twseClient, exitEvaluator, objectMapper,
                scoreConfigProvider, marketRegimeProvider,
                /* reviewLogRepoProvider     */ stubProvider(null),
                /* finalDecisionRepoProvider */ stubProvider(null),
                /* exitLogRepoProvider       */ stubProvider(null),
                /* snapshotServiceProvider   */ stubProvider(null),
                /* staticEnabled */ true
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> stubProvider(T value) {
        org.springframework.beans.factory.ObjectProvider<T> p = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @Test
    void recordEntry_writesOpenRowWithAllFields() {
        PaperTradeEntity result = service.recordEntry(
                "2330",
                new BigDecimal("100.0"),
                new BigDecimal("94.0"),
                new BigDecimal("108.0"),
                new BigDecimal("115.0"),
                1000,
                "AI Decision Score 5"
        );

        assertThat(result).isNotNull();

        ArgumentCaptor<PaperTradeEntity> cap = ArgumentCaptor.forClass(PaperTradeEntity.class);
        verify(repository, times(1)).save(cap.capture());

        PaperTradeEntity saved = cap.getValue();
        assertThat(saved.getSymbol()).isEqualTo("2330");
        assertThat(saved.getStatus()).isEqualTo("OPEN");
        assertThat(saved.getEntryDate()).isEqualTo(LocalDate.now());
        assertThat(saved.getEntryPrice()).isEqualByComparingTo("100.0");
        assertThat(saved.getStopLossPrice()).isEqualByComparingTo("94.0");
        assertThat(saved.getTarget1Price()).isEqualByComparingTo("108.0");
        assertThat(saved.getTarget2Price()).isEqualByComparingTo("115.0");
        assertThat(saved.getPositionShares()).isEqualTo(1000);
        // qty=1000 entryPrice=100 → positionAmount=100000
        assertThat(saved.getPositionAmount()).isEqualByComparingTo("100000.00");
        // payload_json should contain the reason
        assertThat(saved.getPayloadJson()).contains("AI Decision Score 5");
        // tradeId is auto-generated, non-blank
        assertThat(saved.getTradeId()).isNotBlank();
    }

    @Test
    void recordEntry_appliesDefaultsWhenNullStopAndTargets() {
        service.recordEntry("2317", new BigDecimal("100"), null, null, null, null, "default test");

        ArgumentCaptor<PaperTradeEntity> cap = ArgumentCaptor.forClass(PaperTradeEntity.class);
        verify(repository, times(1)).save(cap.capture());
        PaperTradeEntity saved = cap.getValue();

        // -5% / +8% / +15% defaults
        assertThat(saved.getStopLossPrice()).isEqualByComparingTo("95.0000");
        assertThat(saved.getTarget1Price()).isEqualByComparingTo("108.0000");
        assertThat(saved.getTarget2Price()).isEqualByComparingTo("115.0000");
        assertThat(saved.getMaxHoldingDays()).isEqualTo(5);
        assertThat(saved.getStrategyType()).isEqualTo("SETUP");
        assertThat(saved.getSource()).isEqualTo("MANUAL");
    }

    /**
     * Idempotency contract: 同 symbol + 同 entry_date 已存在 OPEN row 時直接回傳既有 row,不再呼叫 save。
     */
    @Test
    void recordEntry_idempotentWhenSameSymbolAlreadyOpenToday() {
        PaperTradeEntity existing = new PaperTradeEntity();
        existing.setStatus("OPEN");
        existing.setSymbol("2330");
        existing.setEntryDate(LocalDate.now());
        existing.setEntryPrice(new BigDecimal("99"));

        when(repository.findByEntryDateAndSymbol(LocalDate.now(), "2330"))
                .thenReturn(List.of(existing));

        PaperTradeEntity result = service.recordEntry(
                "2330", new BigDecimal("100"), null, null, null, null, "second call"
        );

        assertThat(result).isSameAs(existing);
        verify(repository, never()).save(any());
    }

    /**
     * 已存在但狀態為 CLOSED → 應該允許開新一筆(不算重複)。
     */
    @Test
    void recordEntry_allowsNewWhenExistingIsClosed() {
        PaperTradeEntity closed = new PaperTradeEntity();
        closed.setStatus("CLOSED");
        closed.setSymbol("2330");
        closed.setEntryDate(LocalDate.now());

        when(repository.findByEntryDateAndSymbol(LocalDate.now(), "2330"))
                .thenReturn(List.of(closed));

        service.recordEntry("2330", new BigDecimal("100"), null, null, null, null, "after close");

        verify(repository, times(1)).save(any(PaperTradeEntity.class));
    }

    @Test
    void recordEntry_skipsWhenPaperModeDisabled() {
        when(scoreConfig.getBoolean(eq("trading.paper_mode.enabled"), anyBoolean()))
                .thenReturn(false);

        PaperTradeEntity result = service.recordEntry(
                "2330", new BigDecimal("100"), null, null, null, null, "disabled"
        );

        assertThat(result).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void recordEntry_rejectsBlankSymbol() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.recordEntry("", new BigDecimal("100"), null, null, null, null, "x")
        );
        verify(repository, never()).save(any());
    }

    @Test
    void recordEntry_rejectsNonPositivePrice() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.recordEntry("2330", BigDecimal.ZERO, null, null, null, null, "x")
        );
    }
}
