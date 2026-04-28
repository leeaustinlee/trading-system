package com.austin.trading.service;

import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.repository.PaperTradeRepository;
import com.austin.trading.repository.PositionRepository;
import com.austin.trading.service.regime.MarketIndexProvider;
import com.austin.trading.service.regime.RealDowngradeEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-unit tests for {@link RealDowngradeEvaluator} (P0.2).
 *
 * <p>Verifies the four hard downgrade triggers (CONSEC_DOWN /
 * TAIEX_BELOW_60MA / SEMI_WEAK / DAILY_LOSS_CAP), the multi-trigger
 * accumulation, the feature-flag short-circuit, and the no-trigger
 * pass-through. ScoreConfigService / MarketIndexProvider /
 * PaperTradeRepository / PositionRepository are mocked so the test runs
 * with no DB dependency.</p>
 */
class MarketRegimeRealDowngradeTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 27);

    private ScoreConfigService    scoreConfig;
    private MarketIndexProvider   indexProvider;
    private PaperTradeRepository  paperTradeRepo;
    private PositionRepository    positionRepo;
    private RealDowngradeEvaluator evaluator;

    @BeforeEach
    void setUp() {
        scoreConfig    = mock(ScoreConfigService.class);
        indexProvider  = mock(MarketIndexProvider.class);
        paperTradeRepo = mock(PaperTradeRepository.class);
        positionRepo   = mock(PositionRepository.class);

        // ── default config: flag ON, all thresholds at engine defaults ──
        lenient().when(scoreConfig.getBoolean(eq(RealDowngradeEvaluator.FLAG_ENABLED), eq(true)))
                .thenReturn(true);
        lenient().when(scoreConfig.getBoolean(anyString(), any(Boolean.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        lenient().when(scoreConfig.getInt(anyString(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(1));
        lenient().when(scoreConfig.getDecimal(anyString(), any(BigDecimal.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        lenient().when(scoreConfig.getString(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));

        // ── default provider: empty data (so no trigger fires unless overridden) ──
        lenient().when(indexProvider.getTaiexCloses(any(), anyInt())).thenReturn(List.of());
        lenient().when(indexProvider.getSymbolCloses(anyString(), any(), anyInt()))
                .thenReturn(List.of());
        lenient().when(indexProvider.getTaiex60DayMa(any())).thenReturn(Optional.empty());

        // ── default repos: no closed paper trades, no open/closed positions ──
        lenient().when(paperTradeRepo.findByStatusAndExitDateBetweenOrderByExitDateDescIdDesc(
                anyString(), any(), any())).thenReturn(List.of());
        lenient().when(positionRepo.findByStatus(anyString())).thenReturn(List.of());
        lenient().when(positionRepo.sumRealizedPnlBetween(any(), any())).thenReturn(null);

        evaluator = new RealDowngradeEvaluator(
                scoreConfig, indexProvider, paperTradeRepo, positionRepo, new ObjectMapper());
    }

    // ── shared helpers ───────────────────────────────────────────────

    /** A "normal A-grade" engine decision used as the baseline input. */
    private MarketRegimeDecision baselineA() {
        return new MarketRegimeDecision(
                TODAY,
                LocalDateTime.now(),
                "BULL_TREND",
                "A",
                true,
                new BigDecimal("1.000"),
                List.of("BREAKOUT_CONTINUATION", "PULLBACK_CONFIRMATION"),
                "regime=BULL_TREND grade=A",
                "[\"grade=A\",\"breadth_positive_ratio=0.65\"]",
                "{\"marketGrade\":\"A\"}",
                MarketRegimeDecision.CONFIDENCE_HIGH,
                List.of()
        );
    }

    private static List<BigDecimal> closes(double... vals) {
        BigDecimal[] arr = new BigDecimal[vals.length];
        for (int i = 0; i < vals.length; i++) arr[i] = BigDecimal.valueOf(vals[i]);
        return List.of(arr);
    }

    private static PaperTradeEntity closedTrade(double pnl) {
        PaperTradeEntity t = new PaperTradeEntity();
        t.setStatus("CLOSED");
        t.setExitDate(TODAY);
        t.setPnlAmount(BigDecimal.valueOf(pnl));
        return t;
    }

    private static void assertDowngradedToC(MarketRegimeDecision d, String... expectedTriggers) {
        assertThat(d.marketGrade()).isEqualTo("C");
        assertThat(d.tradeAllowed()).isFalse();
        assertThat(d.allowedSetupTypes()).isEmpty();
        assertThat(d.riskMultiplier()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(d.summary()).contains("DOWNGRADED");
        for (String trig : expectedTriggers) {
            assertThat(d.reasonsJson()).contains(trig);
            assertThat(d.inputSnapshotJson()).contains(trig);
        }
    }

    // ── tests ────────────────────────────────────────────────────────

    @Test
    void consecDown_3DaysDown_triggersCgrade() {
        // 5 closes, last 3 strictly decreasing → max streak = 3 → fires (>= 3).
        when(indexProvider.getTaiexCloses(any(), anyInt()))
                .thenReturn(closes(20000, 19900, 19800, 19700, 19600));

        MarketRegimeDecision out = evaluator.applyIfNeeded(baselineA(), TODAY);

        assertDowngradedToC(out, RealDowngradeEvaluator.TRIGGER_CONSEC_DOWN);
    }

    @Test
    void taiexBelow60MA_triggersCgrade() {
        // 60 closes: 59 at 20000, today at 19000 → MA = ~19983 > today 19000 → fires.
        // CONSEC_DOWN: 1 down day in last 5 (which are last 5 of 60: 20000,20000,20000,20000,19000)
        //   → max streak = 1 < required 3 → does NOT fire.
        BigDecimal[] arr = new BigDecimal[60];
        for (int i = 0; i < 59; i++) arr[i] = new BigDecimal("20000");
        arr[59] = new BigDecimal("19000");
        when(indexProvider.getTaiexCloses(any(), anyInt())).thenReturn(List.of(arr));

        MarketRegimeDecision out = evaluator.applyIfNeeded(baselineA(), TODAY);

        assertDowngradedToC(out, RealDowngradeEvaluator.TRIGGER_TAIEX_BELOW_60MA);
        // Sanity: only the one trigger should be in the reason text.
        assertThat(out.reasonsJson()).doesNotContain(RealDowngradeEvaluator.TRIGGER_CONSEC_DOWN);
    }

    @Test
    void semiWeak_minus3pct_triggersCgrade() {
        // 2330 6 closes (lookback+1): 1000 → 950 = -5.0% < -3.0% threshold.
        when(indexProvider.getSymbolCloses(eq("2330"), any(), anyInt()))
                .thenReturn(closes(1000, 990, 980, 970, 960, 950));

        MarketRegimeDecision out = evaluator.applyIfNeeded(baselineA(), TODAY);

        assertDowngradedToC(out, RealDowngradeEvaluator.TRIGGER_SEMI_WEAK);
        // Note: closes form 5 consecutive down days → CONSEC_DOWN can also fire if
        // TAIEX provider returned data, but in this test TAIEX provider is empty,
        // so only SEMI_WEAK should appear.
        assertThat(out.reasonsJson()).doesNotContain(RealDowngradeEvaluator.TRIGGER_CONSEC_DOWN);
    }

    @Test
    void dailyLossOver5k_triggersCgrade() {
        // closed paper-trades sum to -6,000 NTD < -5,000 cap → fires.
        when(paperTradeRepo.findByStatusAndExitDateBetweenOrderByExitDateDescIdDesc(
                eq("CLOSED"), eq(TODAY), eq(TODAY)))
                .thenReturn(List.of(closedTrade(-3500), closedTrade(-2500)));

        MarketRegimeDecision out = evaluator.applyIfNeeded(baselineA(), TODAY);

        assertDowngradedToC(out, RealDowngradeEvaluator.TRIGGER_DAILY_LOSS_CAP);
    }

    @Test
    void noTriggers_normalA_grade_preserved() {
        // All providers return empty / no-loss → engine output unchanged.
        // A small positive 2330 return should also not trigger SEMI_WEAK.
        when(indexProvider.getSymbolCloses(eq("2330"), any(), anyInt()))
                .thenReturn(closes(1000, 1005, 1010, 1015, 1020, 1025)); // +2.5%

        MarketRegimeDecision input = baselineA();
        MarketRegimeDecision out   = evaluator.applyIfNeeded(input, TODAY);

        // No-trigger path returns the engine's decision <i>by reference</i>
        // — verify both structural fields and identity to lock the contract.
        assertThat(out).isSameAs(input);
        assertThat(out.marketGrade()).isEqualTo("A");
        assertThat(out.tradeAllowed()).isTrue();
        assertThat(out.regimeType()).isEqualTo("BULL_TREND");
        assertThat(out.allowedSetupTypes()).isNotEmpty();
        assertThat(out.summary()).doesNotContain("DOWNGRADED");
    }

    @Test
    void multipleTriggers_recordsAllReasons() {
        // CONSEC_DOWN: 5 closes strictly decreasing.
        // TAIEX_BELOW_60MA: 60 closes — last is much smaller than the rest.
        // We satisfy both by giving 60 closes with a strong downtrend at the tail
        // *and* an overall MA above today's close.
        BigDecimal[] arr = new BigDecimal[60];
        for (int i = 0; i < 55; i++) arr[i] = new BigDecimal("20000");
        arr[55] = new BigDecimal("19500");
        arr[56] = new BigDecimal("19400");
        arr[57] = new BigDecimal("19300");
        arr[58] = new BigDecimal("19200");
        arr[59] = new BigDecimal("19100"); // last → today's close, well below MA ~19905
        when(indexProvider.getTaiexCloses(any(), anyInt())).thenReturn(List.of(arr));

        // SEMI_WEAK: 2330 -5% over 5 days.
        when(indexProvider.getSymbolCloses(eq("2330"), any(), anyInt()))
                .thenReturn(closes(1000, 990, 980, 970, 960, 950));

        // DAILY_LOSS_CAP: closed PaperTrades total -8,000 NTD.
        when(paperTradeRepo.findByStatusAndExitDateBetweenOrderByExitDateDescIdDesc(
                eq("CLOSED"), eq(TODAY), eq(TODAY)))
                .thenReturn(List.of(closedTrade(-8000)));

        MarketRegimeDecision out = evaluator.applyIfNeeded(baselineA(), TODAY);

        assertDowngradedToC(out,
                RealDowngradeEvaluator.TRIGGER_CONSEC_DOWN,
                RealDowngradeEvaluator.TRIGGER_TAIEX_BELOW_60MA,
                RealDowngradeEvaluator.TRIGGER_SEMI_WEAK,
                RealDowngradeEvaluator.TRIGGER_DAILY_LOSS_CAP);
        assertThat(out.summary()).contains(
                RealDowngradeEvaluator.TRIGGER_CONSEC_DOWN,
                RealDowngradeEvaluator.TRIGGER_TAIEX_BELOW_60MA,
                RealDowngradeEvaluator.TRIGGER_SEMI_WEAK,
                RealDowngradeEvaluator.TRIGGER_DAILY_LOSS_CAP);
    }

    @Test
    void flagDisabled_neverDowngrades() {
        // Hand-disable the flag.
        when(scoreConfig.getBoolean(eq(RealDowngradeEvaluator.FLAG_ENABLED), eq(true)))
                .thenReturn(false);

        // …even though every trigger would otherwise fire.
        BigDecimal[] arr = new BigDecimal[60];
        for (int i = 0; i < 55; i++) arr[i] = new BigDecimal("20000");
        for (int i = 55; i < 60; i++) arr[i] = new BigDecimal("19000").subtract(new BigDecimal(i * 50));
        when(indexProvider.getTaiexCloses(any(), anyInt())).thenReturn(List.of(arr));
        when(indexProvider.getSymbolCloses(eq("2330"), any(), anyInt()))
                .thenReturn(closes(1000, 990, 980, 970, 960, 950));
        when(paperTradeRepo.findByStatusAndExitDateBetweenOrderByExitDateDescIdDesc(
                eq("CLOSED"), eq(TODAY), eq(TODAY)))
                .thenReturn(List.of(closedTrade(-9999)));

        MarketRegimeDecision out = evaluator.applyIfNeeded(baselineA(), TODAY);

        // Pass-through: flag off → engine output preserved verbatim.
        assertThat(out.marketGrade()).isEqualTo("A");
        assertThat(out.tradeAllowed()).isTrue();
        assertThat(out.summary()).doesNotContain("DOWNGRADED");
    }
}
