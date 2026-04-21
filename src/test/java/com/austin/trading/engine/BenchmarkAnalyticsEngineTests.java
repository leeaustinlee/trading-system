package com.austin.trading.engine;

import com.austin.trading.dto.internal.BenchmarkInput;
import com.austin.trading.dto.internal.BenchmarkReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2.2 BenchmarkAnalyticsEngine unit tests.
 */
class BenchmarkAnalyticsEngineTests {

    private BenchmarkAnalyticsEngine engine;
    private static final LocalDate START = LocalDate.of(2026, 4, 14);
    private static final LocalDate END   = LocalDate.of(2026, 4, 21);

    @BeforeEach
    void setUp() {
        engine = new BenchmarkAnalyticsEngine(new ObjectMapper());
    }

    // ── null guards ───────────────────────────────────────────────────────────

    @Test
    void nullInput_returnsNull() {
        assertThat(engine.evaluate(null)).isNull();
    }

    @Test
    void zeroTrades_returnsUnknownVerdicts() {
        BenchmarkInput in = new BenchmarkInput(START, END, null, null, 0, null, null);
        BenchmarkReport r = engine.evaluate(in);
        assertThat(r.marketVerdict()).isEqualTo(BenchmarkAnalyticsEngine.UNKNOWN);
        assertThat(r.themeVerdict()).isEqualTo(BenchmarkAnalyticsEngine.UNKNOWN);
    }

    // ── alpha computation ─────────────────────────────────────────────────────

    @Test
    void computeAlpha_positiveAlpha() {
        BigDecimal alpha = engine.computeAlpha(bd(5.0), bd(2.0));
        assertThat(alpha).isEqualByComparingTo(bd(3.0));
    }

    @Test
    void computeAlpha_negativeAlpha() {
        BigDecimal alpha = engine.computeAlpha(bd(1.0), bd(4.0));
        assertThat(alpha).isEqualByComparingTo(bd(-3.0));
    }

    @Test
    void computeAlpha_nullBenchmark_returnsNull() {
        assertThat(engine.computeAlpha(bd(5.0), null)).isNull();
    }

    // ── verdict ───────────────────────────────────────────────────────────────

    @Test
    void verdict_bigPositiveAlpha_isOutperform() {
        assertThat(engine.verdict(bd(2.0))).isEqualTo(BenchmarkAnalyticsEngine.OUTPERFORM);
    }

    @Test
    void verdict_smallPositiveAlpha_isMatch() {
        assertThat(engine.verdict(bd(0.3))).isEqualTo(BenchmarkAnalyticsEngine.MATCH);
    }

    @Test
    void verdict_zeroAlpha_isMatch() {
        assertThat(engine.verdict(BigDecimal.ZERO)).isEqualTo(BenchmarkAnalyticsEngine.MATCH);
    }

    @Test
    void verdict_smallNegativeAlpha_isMatch() {
        assertThat(engine.verdict(bd(-0.3))).isEqualTo(BenchmarkAnalyticsEngine.MATCH);
    }

    @Test
    void verdict_largeNegativeAlpha_isUnderperform() {
        assertThat(engine.verdict(bd(-2.0))).isEqualTo(BenchmarkAnalyticsEngine.UNDERPERFORM);
    }

    @Test
    void verdict_nullAlpha_isUnknown() {
        assertThat(engine.verdict(null)).isEqualTo(BenchmarkAnalyticsEngine.UNKNOWN);
    }

    // ── full evaluate ─────────────────────────────────────────────────────────

    @Test
    void evaluate_strategyBeatsMarket_outperform() {
        BenchmarkInput in = new BenchmarkInput(START, END, bd(8.0), bd(60.0), 10, bd(2.0), bd(3.0));
        BenchmarkReport r = engine.evaluate(in);

        assertThat(r.marketVerdict()).isEqualTo(BenchmarkAnalyticsEngine.OUTPERFORM);
        assertThat(r.marketAlpha()).isEqualByComparingTo(bd(6.0));
        assertThat(r.tradeCount()).isEqualTo(10);
        assertThat(r.startDate()).isEqualTo(START);
    }

    @Test
    void evaluate_strategyUnderperformsMarket_underperform() {
        BenchmarkInput in = new BenchmarkInput(START, END, bd(1.0), bd(60.0), 5, bd(5.0), bd(5.0));
        BenchmarkReport r = engine.evaluate(in);

        assertThat(r.marketVerdict()).isEqualTo(BenchmarkAnalyticsEngine.UNDERPERFORM);
        assertThat(r.marketAlpha()).isEqualByComparingTo(bd(-4.0));
    }

    @Test
    void evaluate_noMarketData_marketVerdictUnknown() {
        BenchmarkInput in = new BenchmarkInput(START, END, bd(5.0), bd(60.0), 8, null, null);
        BenchmarkReport r = engine.evaluate(in);

        assertThat(r.marketVerdict()).isEqualTo(BenchmarkAnalyticsEngine.UNKNOWN);
        assertThat(r.marketAlpha()).isNull();
    }

    @Test
    void evaluate_populatesPayloadJson() {
        BenchmarkInput in = new BenchmarkInput(START, END, bd(3.0), bd(50.0), 7, bd(1.0), bd(1.0));
        BenchmarkReport r = engine.evaluate(in);
        assertThat(r.payloadJson()).contains("strategyAvgReturn").contains("marketVerdict");
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private static BigDecimal bd(double v) { return BigDecimal.valueOf(v); }
}
