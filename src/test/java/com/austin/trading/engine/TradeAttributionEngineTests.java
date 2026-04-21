package com.austin.trading.engine;

import com.austin.trading.dto.internal.TradeAttributionInput;
import com.austin.trading.dto.internal.TradeAttributionOutput;
import com.austin.trading.entity.PositionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TradeAttributionEngineTests {

    private TradeAttributionEngine engine;
    private static final LocalDate DATE = LocalDate.of(2026, 4, 21);

    @BeforeEach
    void setUp() {
        engine = new TradeAttributionEngine(new ObjectMapper());
    }

    // ── Null guard ────────────────────────────────────────────────────────────

    @Test
    void nullInput_returnsNull() {
        assertThat(engine.evaluate(null)).isNull();
    }

    @Test
    void nullPosition_returnsNull() {
        assertThat(engine.evaluate(new TradeAttributionInput(
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null))).isNull();
    }

    // ── pnlPct computation ────────────────────────────────────────────────────

    @Test
    void pnlPct_positiveReturn() {
        BigDecimal result = engine.computePnlPct(bd(100), bd(110));
        assertThat(result).isEqualByComparingTo(bd(10.0));
    }

    @Test
    void pnlPct_negativeReturn() {
        BigDecimal result = engine.computePnlPct(bd(100), bd(95));
        assertThat(result).isEqualByComparingTo(bd(-5.0));
    }

    @Test
    void pnlPct_nullEntry_returnsNull() {
        assertThat(engine.computePnlPct(null, bd(100))).isNull();
    }

    // ── delayPct computation ──────────────────────────────────────────────────

    @Test
    void delayPct_enteredAtIdeal() {
        assertThat(engine.computeDelayPct(bd(100), bd(100)))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void delayPct_chasedHigh() {
        BigDecimal d = engine.computeDelayPct(bd(100), bd(102));
        assertThat(d).isEqualByComparingTo(bd(2.0));
    }

    @Test
    void delayPct_nullIdeal_returnsNull() {
        assertThat(engine.computeDelayPct(null, bd(100))).isNull();
    }

    // ── Timing quality ────────────────────────────────────────────────────────

    @Test
    void timingQuality_smallDelay_isGood() {
        assertThat(engine.assessTiming(bd(0.3), bd(-1.0))).isEqualTo(TradeAttributionEngine.QUALITY_GOOD);
    }

    @Test
    void timingQuality_moderateDelay_isFair() {
        assertThat(engine.assessTiming(bd(1.0), bd(-1.0))).isEqualTo(TradeAttributionEngine.QUALITY_FAIR);
    }

    @Test
    void timingQuality_largeDelay_isPoor() {
        assertThat(engine.assessTiming(bd(3.0), bd(-1.0))).isEqualTo(TradeAttributionEngine.QUALITY_POOR);
    }

    @Test
    void timingQuality_severeMae_isPoor() {
        assertThat(engine.assessTiming(bd(0.1), bd(-6.0))).isEqualTo(TradeAttributionEngine.QUALITY_POOR);
    }

    @Test
    void timingQuality_nullDelay_isUnknown() {
        assertThat(engine.assessTiming(null, null)).isEqualTo(TradeAttributionEngine.QUALITY_UNKNOWN);
    }

    // ── Exit quality ──────────────────────────────────────────────────────────

    @Test
    void exitQuality_highCaptureRatio_isGood() {
        assertThat(engine.assessExit(bd(7.0), bd(9.0))).isEqualTo(TradeAttributionEngine.QUALITY_GOOD);
    }

    @Test
    void exitQuality_midCaptureRatio_isFair() {
        assertThat(engine.assessExit(bd(4.0), bd(10.0))).isEqualTo(TradeAttributionEngine.QUALITY_FAIR);
    }

    @Test
    void exitQuality_lowCaptureRatio_isPoor() {
        assertThat(engine.assessExit(bd(1.0), bd(10.0))).isEqualTo(TradeAttributionEngine.QUALITY_POOR);
    }

    @Test
    void exitQuality_negativePnl_isPoor() {
        assertThat(engine.assessExit(bd(-2.0), bd(5.0))).isEqualTo(TradeAttributionEngine.QUALITY_POOR);
    }

    @Test
    void exitQuality_nullMfe_isUnknown() {
        assertThat(engine.assessExit(bd(5.0), null)).isEqualTo(TradeAttributionEngine.QUALITY_UNKNOWN);
    }

    // ── Full evaluate round-trip ──────────────────────────────────────────────

    @Test
    void evaluate_fullInput_populatesAllFields() {
        PositionEntity pos = closedPos("TSMC", bd(100), bd(108));
        TradeAttributionInput in = new TradeAttributionInput(
                pos, 1L, "BULL_TREND",
                2L, "BREAKOUT_CONTINUATION", bd(99),
                3L, "HIGH",
                4L, "AI", "MID_TREND",
                5L,
                bd(12.0), bd(-1.5), null
        );

        TradeAttributionOutput out = engine.evaluate(in);

        assertThat(out.symbol()).isEqualTo("TSMC");
        assertThat(out.setupType()).isEqualTo("BREAKOUT_CONTINUATION");
        assertThat(out.regimeType()).isEqualTo("BULL_TREND");
        assertThat(out.themeStage()).isEqualTo("MID_TREND");
        assertThat(out.pnlPct()).isEqualByComparingTo(bd(8.0));
        assertThat(out.delayPct()).isEqualByComparingTo(bd(1.0101)); // (100-99)/99*100
        assertThat(out.timingQuality()).isIn(
                TradeAttributionEngine.QUALITY_GOOD, TradeAttributionEngine.QUALITY_FAIR);
        assertThat(out.exitQuality()).isEqualTo(TradeAttributionEngine.QUALITY_FAIR); // 8/12=67%
        assertThat(out.payloadJson()).contains("TSMC").contains("BULL_TREND");
    }

    @Test
    void evaluate_noUpstreamData_producesUnknownQualities() {
        PositionEntity pos = closedPos("X", bd(50), bd(52));
        TradeAttributionInput in = new TradeAttributionInput(
                pos, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null
        );

        TradeAttributionOutput out = engine.evaluate(in);

        assertThat(out.timingQuality()).isEqualTo(TradeAttributionEngine.QUALITY_UNKNOWN);
        assertThat(out.exitQuality()).isEqualTo(TradeAttributionEngine.QUALITY_UNKNOWN);
        assertThat(out.pnlPct()).isEqualByComparingTo(bd(4.0));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private PositionEntity closedPos(String symbol, BigDecimal entry, BigDecimal exit) {
        PositionEntity p = new PositionEntity();
        p.setSymbol(symbol);
        p.setStatus("CLOSED");
        p.setAvgCost(entry);
        p.setClosePrice(exit);
        p.setOpenedAt(LocalDateTime.of(DATE, java.time.LocalTime.of(9, 30)));
        p.setClosedAt(LocalDateTime.of(DATE.plusDays(3), java.time.LocalTime.of(14, 0)));
        return p;
    }

    private static BigDecimal bd(double v) { return BigDecimal.valueOf(v); }
}
