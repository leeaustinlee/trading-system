package com.austin.trading.engine;

import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.RankedCandidate;
import com.austin.trading.dto.internal.SetupDecision;
import com.austin.trading.dto.internal.SetupEvaluationInput;
import com.austin.trading.dto.internal.ThemeStrengthDecision;
import com.austin.trading.service.ScoreConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-unit tests for {@link SetupEngine}: no DB, ScoreConfigService mocked.
 */
class SetupEngineTests {

    private SetupEngine engine;
    private static final LocalDate DATE = LocalDate.of(2026, 4, 21);

    @BeforeEach
    void setUp() {
        ScoreConfigService config = mock(ScoreConfigService.class);
        when(config.getDecimal(anyString(), any(BigDecimal.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        when(config.getInt(anyString(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(1));
        engine = new SetupEngine(config, new ObjectMapper());
    }

    private RankedCandidate candidate(String symbol) {
        return new RankedCandidate(DATE, symbol,
                new BigDecimal("7.5"), new BigDecimal("7.0"), new BigDecimal("6.0"),
                new BigDecimal("7.0"), "AI_THEME",
                false, true, null, "{}");
    }

    private MarketRegimeDecision bullRegime() {
        return new MarketRegimeDecision(DATE, LocalDateTime.now(), "BULL_TREND", "A",
                true, new BigDecimal("1.0"),
                List.of(SetupEngine.SETUP_BREAKOUT, SetupEngine.SETUP_PULLBACK, SetupEngine.SETUP_EVENT),
                "bull", "[]", "{}");
    }

    private MarketRegimeDecision rangeRegime() {
        return new MarketRegimeDecision(DATE, LocalDateTime.now(), "RANGE_CHOP", "B",
                true, new BigDecimal("0.5"),
                List.of(SetupEngine.SETUP_PULLBACK, SetupEngine.SETUP_EVENT),
                "range", "[]", "{}");
    }

    private MarketRegimeDecision panicRegime() {
        return new MarketRegimeDecision(DATE, LocalDateTime.now(), "PANIC_VOLATILITY", "C",
                false, BigDecimal.ZERO,
                List.of(),
                "panic", "[]", "{}");
    }

    // ── Breakout tests ─────────────────────────────────────────────────────

    @Test
    void breakout_valid_priceAboveBaseHigh() {
        SetupEvaluationInput in = new SetupEvaluationInput(
                candidate("3008"), bullRegime(), null,
                new BigDecimal("102.0"),   // currentPrice >= baseHigh
                new BigDecimal("100.0"),   // prevClose
                null, null,
                new BigDecimal("100.0"),   // baseHigh
                new BigDecimal("90.0"),    // baseLow
                new BigDecimal("105.0"), new BigDecimal("98.0"),
                new BigDecimal("10000"), new BigDecimal("15000"), // vol ok (15k >= 1.5 * 10k)
                5, false
        );

        SetupDecision d = engine.evaluateOne(in);

        assertThat(d.valid()).isTrue();
        assertThat(d.setupType()).isEqualTo(SetupEngine.SETUP_BREAKOUT);
        assertThat(d.idealEntryPrice()).isEqualByComparingTo("100.0"); // = baseHigh
        assertThat(d.initialStopPrice()).isLessThan(d.idealEntryPrice());
        assertThat(d.takeProfit1Price()).isGreaterThan(d.idealEntryPrice());
        assertThat(d.takeProfit2Price()).isGreaterThan(d.takeProfit1Price());
        assertThat(d.trailingMode()).isEqualTo("MA5_TRAIL");
    }

    @Test
    void breakout_rejected_priceTooFarBelow() {
        SetupEvaluationInput in = new SetupEvaluationInput(
                candidate("3008"), bullRegime(), null,
                new BigDecimal("95.0"),    // 5% below baseHigh — outside 2% near zone
                new BigDecimal("94.0"), null, null,
                new BigDecimal("100.0"), new BigDecimal("90.0"),
                null, null, null, null, 5, false
        );

        SetupDecision d = engine.evaluateOne(in);
        // Falls through to PULLBACK — but MA data is null too, so eventually NO_VALID_SETUP
        assertThat(d.valid()).isFalse();
    }

    @Test
    void breakout_rejected_insufficientVolume() {
        SetupEvaluationInput in = new SetupEvaluationInput(
                candidate("3008"), bullRegime(), null,
                new BigDecimal("101.0"),
                new BigDecimal("100.0"), null, null,
                new BigDecimal("100.0"), new BigDecimal("90.0"),
                null, null,
                new BigDecimal("10000"), new BigDecimal("5000"),  // 5k < 1.5 * 10k
                5, false
        );

        SetupDecision d = engine.evaluateOne(in);
        // Falls through breakout (low volume), pullback (no MA), event (not event-driven) → invalid
        assertThat(d.valid()).isFalse();
        assertThat(d.rejectionReason()).contains("EVENT:NOT_EVENT_DRIVEN");
    }

    @Test
    void breakout_missingBaseHigh_triesPullback() {
        // baseHigh=null → breakout rejected → tries pullback
        SetupEvaluationInput in = new SetupEvaluationInput(
                candidate("2330"), bullRegime(), null,
                new BigDecimal("100.0"),
                new BigDecimal("99.0"),
                new BigDecimal("98.0"),    // ma5
                new BigDecimal("97.0"),    // ma10
                null,                       // no baseHigh
                new BigDecimal("95.0"),
                new BigDecimal("105.0"), new BigDecimal("99.0"),
                null, null, 3, false
        );

        SetupDecision d = engine.evaluateOne(in);
        assertThat(d.valid()).isTrue();
        assertThat(d.setupType()).isEqualTo(SetupEngine.SETUP_PULLBACK);
    }

    // ── Pullback tests ─────────────────────────────────────────────────────

    @Test
    void pullback_valid_priceAboveMa10() {
        SetupEvaluationInput in = new SetupEvaluationInput(
                candidate("2382"), bullRegime(), null,
                new BigDecimal("98.5"),    // between ma10=97 and ma5=99
                new BigDecimal("97.0"),
                new BigDecimal("99.0"),    // ma5
                new BigDecimal("97.0"),    // ma10
                new BigDecimal("105.0"),   // baseHigh
                new BigDecimal("94.0"),    // baseLow
                new BigDecimal("106.0"), new BigDecimal("95.0"),
                null, null, 3, false
        );

        SetupDecision d = engine.evaluateOne(in);
        assertThat(d.valid()).isTrue();
        assertThat(d.setupType()).isEqualTo(SetupEngine.SETUP_PULLBACK);
        assertThat(d.trailingMode()).isEqualTo("MA10_TRAIL");
        assertThat(d.holdingWindowDays()).isEqualTo(8);
    }

    @Test
    void pullback_rejected_belowMa10() {
        SetupEvaluationInput in = new SetupEvaluationInput(
                candidate("2382"), bullRegime(), null,
                new BigDecimal("96.0"),    // below ma10=97
                new BigDecimal("97.0"),
                new BigDecimal("99.0"), new BigDecimal("97.0"),
                null, null, null, null, null, null, 3, false
        );

        SetupDecision d = engine.evaluateOne(in);
        assertThat(d.valid()).isFalse();
        assertThat(d.rejectionReason()).contains("PULLBACK:BELOW_MA10");
    }

    @Test
    void pullback_rejected_tooDeeplyPulledBack() {
        // recentSwingHigh=120, currentPrice=108 → depth = 10% > 8% max
        SetupEvaluationInput in = new SetupEvaluationInput(
                candidate("2454"), bullRegime(), null,
                new BigDecimal("108.0"),
                new BigDecimal("107.0"),
                new BigDecimal("109.0"), new BigDecimal("107.0"),
                null, null,
                new BigDecimal("120.0"),   // recentSwingHigh
                new BigDecimal("106.0"),
                null, null, 3, false
        );

        SetupDecision d = engine.evaluateOne(in);
        assertThat(d.valid()).isFalse();
        assertThat(d.rejectionReason()).contains("PULLBACK:TOO_DEEP");
    }

    // ── Event Second Leg tests ──────────────────────────────────────────────

    @Test
    void event_valid_withProperConsolidation() {
        SetupEvaluationInput in = new SetupEvaluationInput(
                candidate("2317"), bullRegime(), null,
                new BigDecimal("50.0"),
                new BigDecimal("49.0"), null, null,
                new BigDecimal("52.0"),    // baseHigh
                new BigDecimal("47.0"),    // baseLow
                null, null, null, null,
                5,                          // consolidationDays in [3, 10]
                true                        // eventDriven
        );

        SetupDecision d = engine.evaluateOne(in);
        assertThat(d.valid()).isTrue();
        assertThat(d.setupType()).isEqualTo(SetupEngine.SETUP_EVENT);
        assertThat(d.holdingWindowDays()).isEqualTo(7);
    }

    @Test
    void event_rejected_notEventDriven() {
        SetupEvaluationInput in = new SetupEvaluationInput(
                candidate("2317"), bullRegime(), null,
                new BigDecimal("50.0"), new BigDecimal("49.0"),
                null, null, null, null, null, null, null, null,
                5, false   // eventDriven = false
        );

        SetupDecision d = engine.evaluateOne(in);
        assertThat(d.valid()).isFalse();
        assertThat(d.rejectionReason()).contains("EVENT:NOT_EVENT_DRIVEN");
    }

    @Test
    void event_rejected_tooManyConsolidationDays() {
        SetupEvaluationInput in = new SetupEvaluationInput(
                candidate("2317"), bullRegime(), null,
                new BigDecimal("50.0"), new BigDecimal("49.0"),
                null, null, null, null, null, null, null, null,
                15, true    // 15 > max 10
        );

        SetupDecision d = engine.evaluateOne(in);
        assertThat(d.valid()).isFalse();
        assertThat(d.rejectionReason()).contains("CONSOLIDATION_OUT_OF_RANGE");
    }

    // ── Regime gate tests ───────────────────────────────────────────────────

    @Test
    void panicRegime_blocksAllSetups() {
        SetupEvaluationInput in = new SetupEvaluationInput(
                candidate("2330"), panicRegime(), null,
                new BigDecimal("800.0"), new BigDecimal("790.0"),
                null, null, new BigDecimal("800.0"), null, null, null, null, null, 5, false
        );

        SetupDecision d = engine.evaluateOne(in);
        assertThat(d.valid()).isFalse();
        assertThat(d.rejectionReason()).startsWith("REGIME_TRADE_NOT_ALLOWED");
    }

    @Test
    void tradeAllowedFalse_blocksEvenWithAllowedTypes() {
        // tradeAllowed=false should block regardless of non-empty allowedSetupTypes
        MarketRegimeDecision weakRegime = new MarketRegimeDecision(DATE, LocalDateTime.now(),
                "WEAK_DOWNTREND", "D", false, new BigDecimal("0.3"),
                List.of(SetupEngine.SETUP_PULLBACK),
                "weak", "[]", "{}");
        SetupEvaluationInput in = new SetupEvaluationInput(
                candidate("2330"), weakRegime, null,
                new BigDecimal("98.0"), new BigDecimal("97.0"),
                new BigDecimal("97.5"), new BigDecimal("97.0"),
                null, null, null, null, null, null, 3, false
        );

        SetupDecision d = engine.evaluateOne(in);
        assertThat(d.valid()).isFalse();
        assertThat(d.rejectionReason()).startsWith("REGIME_TRADE_NOT_ALLOWED");
        assertThat(d.rejectionReason()).contains("WEAK_DOWNTREND");
    }

    @Test
    void nullRegime_conservative_breakoutNotAllowed() {
        // null regime → conservative fallback: only PULLBACK + EVENT allowed
        SetupEvaluationInput in = new SetupEvaluationInput(
                candidate("2330"), null, null,
                new BigDecimal("102.0"), new BigDecimal("100.0"),
                new BigDecimal("99.0"), new BigDecimal("97.0"),
                new BigDecimal("100.0"), new BigDecimal("90.0"),
                null, null, null, null, 3, false
        );

        SetupDecision d = engine.evaluateOne(in);
        // BREAKOUT should be skipped; PULLBACK should succeed
        assertThat(d.valid()).isTrue();
        assertThat(d.setupType()).isEqualTo(SetupEngine.SETUP_PULLBACK);
    }

    @Test
    void rangeRegime_breakoutNotAllowed_triesPullback() {
        // RANGE_CHOP only allows PULLBACK + EVENT; breakout should be skipped
        SetupEvaluationInput in = new SetupEvaluationInput(
                candidate("2330"), rangeRegime(), null,
                new BigDecimal("98.0"),
                new BigDecimal("97.0"),
                new BigDecimal("97.5"), new BigDecimal("97.0"),
                new BigDecimal("100.0"), new BigDecimal("94.0"),
                null, null, null, null, 3, false
        );

        SetupDecision d = engine.evaluateOne(in);
        assertThat(d.valid()).isTrue();
        assertThat(d.setupType()).isEqualTo(SetupEngine.SETUP_PULLBACK);
    }

    @Test
    void themeNotTradable_blocksEntry() {
        ThemeStrengthDecision theme = new ThemeStrengthDecision(
                DATE, "AI_THEME", new BigDecimal("3.0"), "DECAY",
                "EARNINGS", false, new BigDecimal("0.9"), "[]"
        );
        SetupEvaluationInput in = new SetupEvaluationInput(
                candidate("2330"), bullRegime(), theme,
                new BigDecimal("102.0"), new BigDecimal("100.0"),
                null, null, new BigDecimal("100.0"), null, null, null, null, null, 5, false
        );

        SetupDecision d = engine.evaluateOne(in);
        assertThat(d.valid()).isFalse();
        assertThat(d.rejectionReason()).contains("THEME_NOT_TRADABLE");
    }

    @Test
    void missingCurrentPrice_invalid() {
        SetupEvaluationInput in = new SetupEvaluationInput(
                candidate("2330"), bullRegime(), null,
                null,  // no price
                null, null, null, null, null, null, null, null, null, 0, false
        );

        SetupDecision d = engine.evaluateOne(in);
        assertThat(d.valid()).isFalse();
        assertThat(d.rejectionReason()).isEqualTo("MISSING_CURRENT_PRICE");
    }

    @Test
    void payloadJson_isParseable() {
        SetupEvaluationInput in = new SetupEvaluationInput(
                candidate("3008"), bullRegime(), null,
                new BigDecimal("102.0"), new BigDecimal("100.0"),
                null, null, new BigDecimal("100.0"), new BigDecimal("90.0"),
                null, null,
                new BigDecimal("10000"), new BigDecimal("16000"),
                5, false
        );

        SetupDecision d = engine.evaluateOne(in);
        assertThat(d.payloadJson()).isNotNull();
        assertThat(d.payloadJson()).startsWith("{");
        assertThat(d.payloadJson()).contains("setupType");
    }
}
