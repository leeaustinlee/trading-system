package com.austin.trading.engine.exit;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FixedRuleExitEvaluatorSanityTest {

    private final FixedRuleExitEvaluator evaluator = new FixedRuleExitEvaluator();

    @Test
    void target1BelowEntryDoesNotTriggerFalseTakeProfit() {
        var entry = snapshot("100", "95", "99", "110");
        var bar = new ExitRuleEvaluator.DailyBar(LocalDate.of(2026, 5, 15),
                bd("105"), bd("100"), bd("104"), 1);
        var result = evaluator.evaluate(entry, bar);
        assertThat(result.shouldExit()).isFalse();
    }

    @Test
    void target2BelowTarget1IsIgnoredButStopStillHasPriority() {
        var entry = snapshot("100", "95", "108", "107");
        var bar = new ExitRuleEvaluator.DailyBar(LocalDate.of(2026, 5, 15),
                bd("109"), bd("94"), bd("104"), 1);
        var result = evaluator.evaluate(entry, bar);
        assertThat(result.shouldExit()).isTrue();
        assertThat(result.reason()).isEqualTo(ExitRuleEvaluator.ExitReason.STOP_LOSS);
    }

    private ExitRuleEvaluator.EntrySnapshot snapshot(String entry, String stop, String tp1, String tp2) {
        return new ExitRuleEvaluator.EntrySnapshot(LocalDate.of(2026, 5, 14),
                bd(entry), bd(stop), bd(tp1), bd(tp2), 5);
    }

    private BigDecimal bd(String v) { return new BigDecimal(v); }
}
