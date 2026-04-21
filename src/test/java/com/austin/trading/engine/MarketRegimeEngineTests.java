package com.austin.trading.engine;

import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.MarketRegimeInput;
import com.austin.trading.service.ScoreConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-unit tests for {@link MarketRegimeEngine}: no DB, ScoreConfigService mocked
 * to always return the supplied default so that test inputs drive classification.
 */
class MarketRegimeEngineTests {

    private MarketRegimeEngine engine;

    @BeforeEach
    void setUp() {
        ScoreConfigService config = mock(ScoreConfigService.class);
        // return the default value supplied by the engine (no DB overrides in unit tests)
        when(config.getDecimal(anyString(), any(BigDecimal.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        engine = new MarketRegimeEngine(config, new ObjectMapper());
    }

    private MarketRegimeInput.Builder baseBuilder() {
        return new MarketRegimeInput.Builder()
                .tradingDate(LocalDate.of(2026, 4, 21))
                .evaluatedAt(LocalDateTime.of(2026, 4, 21, 10, 0))
                .marketGrade("B")
                .marketPhase("高檔震盪期")
                .tsmcTrendScore(new BigDecimal("0.2"))
                .breadthPositiveRatio(new BigDecimal("0.50"))
                .breadthNegativeRatio(new BigDecimal("0.30"))
                .leadersStrongRatio(new BigDecimal("0.50"))
                .indexDistanceFromMa10Pct(new BigDecimal("0.0"))
                .indexDistanceFromMa20Pct(new BigDecimal("0.0"))
                .intradayVolatilityPct(new BigDecimal("1.0"))
                .washoutRebound(false)
                .nearHighNotBreak(false)
                .blowoffSignal(false);
    }

    @Test
    void bullTrend_allChecksPass() {
        MarketRegimeDecision d = engine.evaluate(baseBuilder()
                .marketGrade("A")
                .breadthPositiveRatio(new BigDecimal("0.65"))
                .leadersStrongRatio(new BigDecimal("0.70"))
                .indexDistanceFromMa10Pct(new BigDecimal("1.2"))
                .indexDistanceFromMa20Pct(new BigDecimal("2.5"))
                .build());

        assertThat(d.regimeType()).isEqualTo(MarketRegimeEngine.REGIME_BULL_TREND);
        assertThat(d.tradeAllowed()).isTrue();
        assertThat(d.allowedSetupTypes())
                .containsExactly(MarketRegimeEngine.SETUP_BREAKOUT,
                        MarketRegimeEngine.SETUP_PULLBACK,
                        MarketRegimeEngine.SETUP_EVENT_2ND);
        assertThat(d.riskMultiplier()).isEqualByComparingTo(new BigDecimal("1.000"));
    }

    @Test
    void rangeChop_gradeBWithMixedBreadth() {
        MarketRegimeDecision d = engine.evaluate(baseBuilder()
                .marketGrade("B")
                .breadthPositiveRatio(new BigDecimal("0.45"))
                .breadthNegativeRatio(new BigDecimal("0.35"))
                .leadersStrongRatio(new BigDecimal("0.50"))
                .indexDistanceFromMa10Pct(new BigDecimal("0.5"))
                .indexDistanceFromMa20Pct(new BigDecimal("0.2"))
                .build());

        assertThat(d.regimeType()).isEqualTo(MarketRegimeEngine.REGIME_RANGE_CHOP);
        assertThat(d.tradeAllowed()).isTrue();
        assertThat(d.allowedSetupTypes())
                .containsExactly(MarketRegimeEngine.SETUP_PULLBACK, MarketRegimeEngine.SETUP_EVENT_2ND);
        assertThat(d.riskMultiplier()).isEqualByComparingTo(new BigDecimal("0.500"));
    }

    @Test
    void weakDowntrend_belowMa20AndWeakLeaders() {
        MarketRegimeDecision d = engine.evaluate(baseBuilder()
                .marketGrade("C")
                .breadthPositiveRatio(new BigDecimal("0.30"))
                .breadthNegativeRatio(new BigDecimal("0.60"))
                .leadersStrongRatio(new BigDecimal("0.25"))
                .indexDistanceFromMa10Pct(new BigDecimal("-1.0"))
                .indexDistanceFromMa20Pct(new BigDecimal("-2.5"))
                .build());

        assertThat(d.regimeType()).isEqualTo(MarketRegimeEngine.REGIME_WEAK_DOWNTREND);
        assertThat(d.tradeAllowed()).isTrue(); // still allowed but only pullback
        assertThat(d.allowedSetupTypes()).containsExactly(MarketRegimeEngine.SETUP_PULLBACK);
        assertThat(d.riskMultiplier()).isEqualByComparingTo(new BigDecimal("0.250"));
    }

    @Test
    void panicVolatility_highIntradayVolatility() {
        MarketRegimeDecision d = engine.evaluate(baseBuilder()
                .intradayVolatilityPct(new BigDecimal("3.0"))
                .build());

        assertThat(d.regimeType()).isEqualTo(MarketRegimeEngine.REGIME_PANIC_VOLATILITY);
        assertThat(d.tradeAllowed()).isFalse();
        assertThat(d.allowedSetupTypes()).isEmpty();
        assertThat(d.riskMultiplier()).isEqualByComparingTo(new BigDecimal("0.000"));
    }

    @Test
    void panicVolatility_blowoffSignalTrumpsOtherFields() {
        // Even when breadth looks fine, an explicit blowoff signal forces panic.
        MarketRegimeDecision d = engine.evaluate(baseBuilder()
                .marketGrade("A")
                .breadthPositiveRatio(new BigDecimal("0.80"))
                .leadersStrongRatio(new BigDecimal("0.90"))
                .indexDistanceFromMa10Pct(new BigDecimal("3.0"))
                .indexDistanceFromMa20Pct(new BigDecimal("4.0"))
                .blowoffSignal(true)
                .build());

        assertThat(d.regimeType()).isEqualTo(MarketRegimeEngine.REGIME_PANIC_VOLATILITY);
        assertThat(d.tradeAllowed()).isFalse();
    }

    @Test
    void panicVolatility_extremeNegativeBreadth() {
        MarketRegimeDecision d = engine.evaluate(baseBuilder()
                .breadthNegativeRatio(new BigDecimal("0.80"))
                .build());

        assertThat(d.regimeType()).isEqualTo(MarketRegimeEngine.REGIME_PANIC_VOLATILITY);
    }

    @Test
    void fallbackDefaults_whenAllFieldsNull() {
        // All numeric fields null → engine substitutes defaults → RANGE_CHOP.
        MarketRegimeInput.Builder b = new MarketRegimeInput.Builder()
                .tradingDate(LocalDate.now())
                .evaluatedAt(LocalDateTime.now())
                .marketGrade(null)
                .marketPhase(null)
                .tsmcTrendScore(null)
                .breadthPositiveRatio(null)
                .breadthNegativeRatio(null)
                .leadersStrongRatio(null)
                .indexDistanceFromMa10Pct(null)
                .indexDistanceFromMa20Pct(null)
                .intradayVolatilityPct(null)
                .washoutRebound(false)
                .nearHighNotBreak(false)
                .blowoffSignal(false);
        MarketRegimeDecision d = engine.evaluate(b.build());
        assertThat(d.regimeType()).isEqualTo(MarketRegimeEngine.REGIME_RANGE_CHOP);
        assertThat(d.inputSnapshotJson()).contains("\"breadthPositiveRatio\":null");
    }

    @Test
    void reasonsJson_isParseable() {
        MarketRegimeDecision d = engine.evaluate(baseBuilder().build());
        assertThat(d.reasonsJson()).isNotNull();
        assertThat(d.reasonsJson()).startsWith("[").endsWith("]");
    }
}
