package com.austin.trading.engine;

import com.austin.trading.engine.PositionDecisionEngine.PositionDecisionResult;
import com.austin.trading.engine.PositionDecisionEngine.PositionStatus;
import com.austin.trading.engine.PositionDecisionEngine.TrailingAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2.3 ExitRegimeIntegrationEngine unit tests.
 */
class ExitRegimeIntegrationEngineTests {

    private ExitRegimeIntegrationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ExitRegimeIntegrationEngine();
    }

    // ── null / edge cases ─────────────────────────────────────────────────────

    @Test
    void nullBase_returnsNull() {
        assertThat(engine.applyOverride(null, "BULL_TREND", null)).isNull();
    }

    @Test
    void alreadyExit_noOverride() {
        PositionDecisionResult base = result(PositionStatus.EXIT, "stop_loss");
        assertThat(engine.applyOverride(base, "PANIC_VOLATILITY", "DECAY")).isSameAs(base);
    }

    // ── Rule 1: PANIC_VOLATILITY → EXIT ──────────────────────────────────────

    @Test
    void panicVolatility_forcesExit() {
        PositionDecisionResult base = result(PositionStatus.HOLD, "normal");
        PositionDecisionResult out = engine.applyOverride(base, "PANIC_VOLATILITY", null);
        assertThat(out.status()).isEqualTo(PositionStatus.EXIT);
        assertThat(out.reason()).isEqualTo(ExitRegimeIntegrationEngine.REASON_REGIME_PANIC);
    }

    @Test
    void panicVolatility_overridesStrongToo() {
        PositionDecisionResult base = result(PositionStatus.STRONG, "trend_up");
        PositionDecisionResult out = engine.applyOverride(base, "PANIC_VOLATILITY", "MID_TREND");
        assertThat(out.status()).isEqualTo(PositionStatus.EXIT);
    }

    // ── Rule 2: WEAK_DOWNTREND + DECAY → EXIT ────────────────────────────────

    @Test
    void weakDowntrend_andDecayTheme_forcesExit() {
        PositionDecisionResult base = result(PositionStatus.HOLD, "normal");
        PositionDecisionResult out = engine.applyOverride(base, "WEAK_DOWNTREND", "DECAY");
        assertThat(out.status()).isEqualTo(PositionStatus.EXIT);
        assertThat(out.reason()).isEqualTo(ExitRegimeIntegrationEngine.REASON_REGIME_WEAK_THEME_DECAY);
    }

    @Test
    void weakDowntrend_withNonDecayTheme_doesNotForceExit() {
        PositionDecisionResult base = result(PositionStatus.HOLD, "normal");
        PositionDecisionResult out = engine.applyOverride(base, "WEAK_DOWNTREND", "MID_TREND");
        assertThat(out.status()).isNotEqualTo(PositionStatus.EXIT);
    }

    // ── Rule 3: DECAY theme + non-BULL → WEAKEN ──────────────────────────────

    @Test
    void decayTheme_rangeChop_holdBecomesWeaken() {
        PositionDecisionResult base = result(PositionStatus.HOLD, "hold");
        PositionDecisionResult out = engine.applyOverride(base, "RANGE_CHOP", "DECAY");
        assertThat(out.status()).isEqualTo(PositionStatus.WEAKEN);
        assertThat(out.reason()).isEqualTo(ExitRegimeIntegrationEngine.REASON_THEME_DECAY);
    }

    @Test
    void decayTheme_rangeChop_strongBecomesWeaken() {
        PositionDecisionResult base = result(PositionStatus.STRONG, "trending");
        PositionDecisionResult out = engine.applyOverride(base, "RANGE_CHOP", "DECAY");
        assertThat(out.status()).isEqualTo(PositionStatus.WEAKEN);
    }

    @Test
    void decayTheme_bullTrend_noOverride() {
        // BULL_TREND + DECAY → no downgrade
        PositionDecisionResult base = result(PositionStatus.HOLD, "hold");
        PositionDecisionResult out = engine.applyOverride(base, "BULL_TREND", "DECAY");
        assertThat(out).isSameAs(base);
    }

    @Test
    void decayTheme_alreadyWeaken_noChange() {
        PositionDecisionResult base = result(PositionStatus.WEAKEN, "prev_weaken");
        PositionDecisionResult out = engine.applyOverride(base, "RANGE_CHOP", "DECAY");
        // WEAKEN is not STRONG/HOLD → rule 3 doesn't apply → return original
        assertThat(out).isSameAs(base);
    }

    // ── no override ───────────────────────────────────────────────────────────

    @Test
    void bullTrend_midTrend_noOverride() {
        PositionDecisionResult base = result(PositionStatus.HOLD, "normal");
        assertThat(engine.applyOverride(base, "BULL_TREND", "MID_TREND")).isSameAs(base);
    }

    @Test
    void nullRegime_noOverride() {
        PositionDecisionResult base = result(PositionStatus.HOLD, "normal");
        assertThat(engine.applyOverride(base, null, null)).isSameAs(base);
    }

    // ── wouldOverride ─────────────────────────────────────────────────────────

    @Test
    void wouldOverride_panicVolatility_true() {
        assertThat(engine.wouldOverride("PANIC_VOLATILITY", null)).isTrue();
    }

    @Test
    void wouldOverride_bullTrend_midTrend_false() {
        assertThat(engine.wouldOverride("BULL_TREND", "MID_TREND")).isFalse();
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private static PositionDecisionResult result(PositionStatus status, String reason) {
        return new PositionDecisionResult(status, reason, BigDecimal.ZERO, TrailingAction.NONE);
    }
}
