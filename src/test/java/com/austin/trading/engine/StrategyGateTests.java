package com.austin.trading.engine;

import com.austin.trading.domain.enums.StrategyType;
import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyGateTests {

    @Test
    void classifierClassifiesBreakoutPullbackContinuation() {
        StrategyClassifier classifier = new StrategyClassifier();
        assertThat(classifier.classify(candidate(true, false, true, 1.8, bd("1.8"))).primaryStrategy())
                .isEqualTo(StrategyType.BREAKOUT);
        assertThat(classifier.classify(candidate(false, true, false, 1.8, null)).primaryStrategy())
                .isEqualTo(StrategyType.PULLBACK);
        assertThat(classifier.classify(candidate(false, false, false, 1.4, bd("1.5"))).primaryStrategy())
                .isEqualTo(StrategyType.MOMENTUM_CONTINUATION);
    }

    @Test
    void breakoutGateDoesNotRejectNearDayHighDirectly() {
        var base = new StrategyClassifier().classify(candidate(true, false, false, 1.2, bd("2")));
        var out = new BreakoutGate().evaluate(candidate(true, false, false, 1.2, bd("2")), base);
        assertThat(out.gateStatus()).isIn("WATCH", "ENTER_SMALL");
        assertThat(out.rejected()).isFalse();
    }

    @Test
    void pullbackGateRequiresRrAndSupport() {
        var c = candidate(false, true, false, 1.0, null);
        var out = new PullbackGate().evaluate(c, new StrategyClassifier().classify(c));
        assertThat(out.gateStatus()).startsWith("REJECT");
    }

    @Test
    void continuationGateAcceptsMediumRr() {
        var c = candidate(false, false, true, 1.3, bd("1.4"));
        var out = new ContinuationGate().evaluate(c, new StrategyClassifier().classify(c));
        assertThat(out.gateStatus()).isIn("WATCH", "ENTER_SMALL");
    }

    private static FinalDecisionCandidateRequest candidate(boolean nearHigh, boolean belowOpen,
                                                           boolean entryTriggered, double rr,
                                                           BigDecimal volumeRatio) {
        return new FinalDecisionCandidateRequest(
                "6770", "力積電", "合理", "SETUP", rr,
                true, true, false, belowOpen, false, nearHigh, true,
                "r", belowOpen ? "50-52" : "60-62", 48.0, 70.0, 75.0,
                null, null, null, null, false, bd("7"), true, 1, bd("8"),
                null, null, false, false, false, entryTriggered,
                bd("60"), bd("59"), bd("58"), bd("59"), volumeRatio,
                bd("0.01"), bd("-0.01"), "BULL_TREND", bd("61"), null);
    }

    private static BigDecimal bd(String s) { return new BigDecimal(s); }
}
