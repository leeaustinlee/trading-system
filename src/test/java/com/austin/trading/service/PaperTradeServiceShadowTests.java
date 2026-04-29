package com.austin.trading.service;

import com.austin.trading.client.TwseMisClient;
import com.austin.trading.engine.exit.FixedRuleExitEvaluator;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.repository.FinalDecisionRepository;
import com.austin.trading.repository.PaperTradeExitLogRepository;
import com.austin.trading.repository.PaperTradeRepository;
import com.austin.trading.repository.PositionReviewLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0.6：PaperTradeService.recordShadowEntry 行為驗證。
 *
 * <p>覆蓋條件：</p>
 * <ul>
 *   <li>final_score >= 6.0 + paper.shadow.enabled=true → 寫入 is_shadow=true</li>
 *   <li>final_score < 6.0 → 不寫入</li>
 *   <li>paper.shadow.enabled=false → 不寫入</li>
 *   <li>同 (entry_date, symbol, is_shadow=true) 已 OPEN → idempotent skip</li>
 *   <li>不會誤觸 LINE / 不會建 real trade</li>
 * </ul>
 */
class PaperTradeServiceShadowTests {

    private PaperTradeRepository repository;
    private ScoreConfigService scoreConfig;
    private PaperTradeService service;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        repository = mock(PaperTradeRepository.class);
        scoreConfig = mock(ScoreConfigService.class);
        TwseMisClient twse = mock(TwseMisClient.class);
        FixedRuleExitEvaluator exitEvaluator = mock(FixedRuleExitEvaluator.class);

        // shadow 的 default：enabled=true, score_min=6.0
        lenient().when(scoreConfig.getBoolean(eq("paper.shadow.enabled"), anyBoolean()))
                .thenReturn(true);
        lenient().when(scoreConfig.getDecimal(eq("paper.shadow.score_min"), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("6.0"));
        lenient().when(scoreConfig.getDecimal(eq("paper.entry_slippage_pct"), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("0.001"));
        lenient().when(scoreConfig.getDecimal(eq("paper.exit_slippage_pct"), any(BigDecimal.class)))
                .thenReturn(new BigDecimal("0.001"));
        lenient().when(scoreConfig.getBoolean(eq("trading.paper_mode.enabled"), anyBoolean()))
                .thenReturn(true);

        ObjectProvider<ScoreConfigService> cfgProvider = mock(ObjectProvider.class);
        when(cfgProvider.getIfAvailable()).thenReturn(scoreConfig);

        ObjectProvider<MarketRegimeService> regimeProvider = mock(ObjectProvider.class);
        when(regimeProvider.getIfAvailable()).thenReturn(null); // currentRegimeType 走 unknown

        when(repository.findByEntryDateAndSymbol(any(LocalDate.class), anyString()))
                .thenReturn(List.of());
        when(repository.save(any(PaperTradeEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ObjectProvider<PositionReviewLogRepository> reviewLogProvider = mock(ObjectProvider.class);
        ObjectProvider<FinalDecisionRepository> fdRepoProvider = mock(ObjectProvider.class);
        ObjectProvider<PaperTradeExitLogRepository> exitLogProvider = mock(ObjectProvider.class);
        ObjectProvider<PaperTradeSnapshotService> snapshotProvider = mock(ObjectProvider.class);
        when(snapshotProvider.getIfAvailable()).thenReturn(null);

        service = new PaperTradeService(
                repository, twse, exitEvaluator, new ObjectMapper(),
                cfgProvider, regimeProvider,
                reviewLogProvider, fdRepoProvider, exitLogProvider, snapshotProvider,
                true);
    }

    @Test
    void scoreAtThreshold_writesShadow() {
        PaperTradeEntity saved = service.recordShadowEntry(
                "2330", "台積電",
                new BigDecimal("100.00"),
                new BigDecimal("95.00"),
                new BigDecimal("108.00"),
                new BigDecimal("115.00"),
                new BigDecimal("6.0"), // 剛好門檻
                "B", "SETUP", "半導體/IC", "test"
        );
        assertThat(saved).isNotNull();
        assertThat(saved.isShadow()).isTrue();
        assertThat(saved.getStatus()).isEqualTo("OPEN");
        assertThat(saved.getSource()).isEqualTo("SHADOW");
        assertThat(saved.getEntryGrade()).isEqualTo("B");
        assertThat(saved.getFinalRankScore()).isEqualByComparingTo("6.0");
        verify(repository, times(1)).save(any(PaperTradeEntity.class));
    }

    @Test
    void scoreBelowMin_doesNotWrite() {
        PaperTradeEntity saved = service.recordShadowEntry(
                "2330", "台積電",
                new BigDecimal("100.00"),
                null, null, null,
                new BigDecimal("5.99"), // 低於 6.0
                "B", "SETUP", null, null
        );
        assertThat(saved).isNull();
        verify(repository, never()).save(any(PaperTradeEntity.class));
    }

    @Test
    void shadowDisabled_doesNotWrite() {
        when(scoreConfig.getBoolean(eq("paper.shadow.enabled"), anyBoolean())).thenReturn(false);

        PaperTradeEntity saved = service.recordShadowEntry(
                "2330", "台積電",
                new BigDecimal("100.00"),
                null, null, null,
                new BigDecimal("8.5"),
                "A_PLUS", "SETUP", null, null
        );
        assertThat(saved).isNull();
        verify(repository, never()).save(any(PaperTradeEntity.class));
    }

    @Test
    void idempotent_existingShadowOpenForSameSymbol_skip() {
        PaperTradeEntity existing = new PaperTradeEntity();
        existing.setShadow(true);
        existing.setStatus("OPEN");
        existing.setSymbol("2330");
        existing.setEntryDate(LocalDate.now());
        when(repository.findByEntryDateAndSymbol(any(LocalDate.class), eq("2330")))
                .thenReturn(List.of(existing));

        PaperTradeEntity saved = service.recordShadowEntry(
                "2330", "台積電",
                new BigDecimal("100.00"),
                null, null, null,
                new BigDecimal("7.0"),
                "A", "SETUP", null, null
        );
        // 回傳 existing 但不 re-save
        assertThat(saved).isSameAs(existing);
        verify(repository, never()).save(any(PaperTradeEntity.class));
    }

    @Test
    void invalidEntryPrice_returnsNull() {
        PaperTradeEntity saved1 = service.recordShadowEntry(
                "2330", null, null, null, null, null,
                new BigDecimal("7.0"), "A", "SETUP", null, null);
        assertThat(saved1).isNull();

        PaperTradeEntity saved2 = service.recordShadowEntry(
                "2330", null, BigDecimal.ZERO, null, null, null,
                new BigDecimal("7.0"), "A", "SETUP", null, null);
        assertThat(saved2).isNull();

        PaperTradeEntity saved3 = service.recordShadowEntry(
                null, null, new BigDecimal("100.00"), null, null, null,
                new BigDecimal("7.0"), "A", "SETUP", null, null);
        assertThat(saved3).isNull();

        verify(repository, never()).save(any(PaperTradeEntity.class));
    }

    @Test
    void shadow_doesNotReuseLiveSlot_separateOpenLinesAllowed() {
        // existing live OPEN 同 symbol 不應該擋掉 shadow（is_shadow 區別獨立）
        PaperTradeEntity liveOpen = new PaperTradeEntity();
        liveOpen.setShadow(false);
        liveOpen.setStatus("OPEN");
        liveOpen.setSymbol("2330");
        liveOpen.setEntryDate(LocalDate.now());
        when(repository.findByEntryDateAndSymbol(any(LocalDate.class), eq("2330")))
                .thenReturn(List.of(liveOpen));

        PaperTradeEntity saved = service.recordShadowEntry(
                "2330", "台積電",
                new BigDecimal("100.00"),
                null, null, null,
                new BigDecimal("7.5"),
                "A", "SETUP", null, null
        );
        // shadow 不被 live 擋
        assertThat(saved).isNotNull();
        assertThat(saved.isShadow()).isTrue();
        verify(repository, times(1)).save(any(PaperTradeEntity.class));
    }
}
