package com.austin.trading.engine.exit;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FixedRuleExitEvaluatorTest {

    private final FixedRuleExitEvaluator evaluator = new FixedRuleExitEvaluator();

    private ExitRuleEvaluator.EntrySnapshot snapshot(double entry, double stop, double t1, double t2, int maxDays) {
        return new ExitRuleEvaluator.EntrySnapshot(
                LocalDate.of(2026, 4, 21),
                BigDecimal.valueOf(entry),
                BigDecimal.valueOf(stop),
                BigDecimal.valueOf(t1),
                BigDecimal.valueOf(t2),
                maxDays);
    }

    private ExitRuleEvaluator.DailyBar bar(double high, double low, double close, int holdingDays) {
        return new ExitRuleEvaluator.DailyBar(
                LocalDate.of(2026, 4, 22),
                BigDecimal.valueOf(high),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(close),
                holdingDays);
    }

    @Test
    void hold_whenNoConditionMet() {
        var entry = snapshot(100, 95, 108, 115, 5);
        var bar = bar(101, 99, 100, 1);
        var ed = evaluator.evaluate(entry, bar);
        assertThat(ed.shouldExit()).isFalse();
        assertThat(ed.mfePct()).isEqualByComparingTo("1.0000");
        assertThat(ed.maePct()).isEqualByComparingTo("-1.0000");
    }

    @Test
    void stopLoss_priorityOverTargetWhenBarTouchesBoth() {
        var entry = snapshot(100, 95, 108, 115, 5);
        // bar 同時觸及停損 94 與 T1 109 — 規則:停損優先
        var bar = bar(109, 94, 100, 2);
        var ed = evaluator.evaluate(entry, bar);
        assertThat(ed.shouldExit()).isTrue();
        assertThat(ed.reason()).isEqualTo(ExitRuleEvaluator.ExitReason.STOP_LOSS);
        assertThat(ed.exitPrice()).isEqualByComparingTo("95");
    }

    @Test
    void target2_priorityOverTarget1() {
        var entry = snapshot(100, 95, 108, 115, 5);
        var bar = bar(116, 100, 116, 2);
        var ed = evaluator.evaluate(entry, bar);
        assertThat(ed.shouldExit()).isTrue();
        assertThat(ed.reason()).isEqualTo(ExitRuleEvaluator.ExitReason.TARGET_2);
        assertThat(ed.exitPrice()).isEqualByComparingTo("115");
    }

    @Test
    void target1_whenOnlyT1Touched() {
        var entry = snapshot(100, 95, 108, 115, 5);
        var bar = bar(110, 100, 109, 2);
        var ed = evaluator.evaluate(entry, bar);
        assertThat(ed.shouldExit()).isTrue();
        assertThat(ed.reason()).isEqualTo(ExitRuleEvaluator.ExitReason.TARGET_1);
        assertThat(ed.exitPrice()).isEqualByComparingTo("108");
    }

    @Test
    void timeExit_whenHoldingDaysReached() {
        var entry = snapshot(100, 95, 108, 115, 5);
        var bar = bar(102, 99, 101, 5);
        var ed = evaluator.evaluate(entry, bar);
        assertThat(ed.shouldExit()).isTrue();
        assertThat(ed.reason()).isEqualTo(ExitRuleEvaluator.ExitReason.TIME_EXIT);
        assertThat(ed.exitPrice()).isEqualByComparingTo("101");
    }

    @Test
    void hold_whenInputsNullOrInvalid() {
        var entry = snapshot(0, 0, 0, 0, 5);
        var bar = bar(100, 99, 100, 1);
        var ed = evaluator.evaluate(entry, bar);
        assertThat(ed.shouldExit()).isFalse();
    }
}
