package com.austin.trading.engine;

import com.austin.trading.domain.enums.StrategyType;
import com.austin.trading.dto.internal.StrategyGateResult;
import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class StrategyClassifier {

    public StrategyGateResult classify(FinalDecisionCandidateRequest c) {
        BigDecimal breakout = BigDecimal.ZERO;
        BigDecimal pullback = BigDecimal.ZERO;
        BigDecimal continuation = BigDecimal.ZERO;

        if (Boolean.TRUE.equals(c.entryTriggered())) breakout = breakout.add(new BigDecimal("4"));
        if (Boolean.TRUE.equals(c.nearDayHigh())) breakout = breakout.add(new BigDecimal("3"));
        if (c.volumeRatio() != null && c.volumeRatio().compareTo(new BigDecimal("1.2")) >= 0) {
            breakout = breakout.add(new BigDecimal("2"));
            continuation = continuation.add(new BigDecimal("2"));
        }
        if (Boolean.TRUE.equals(c.belowOpen()) || Boolean.TRUE.equals(c.belowPrevClose())) {
            pullback = pullback.add(new BigDecimal("3"));
        }
        if (c.riskRewardRatio() != null && c.riskRewardRatio() >= 1.5) pullback = pullback.add(new BigDecimal("2"));
        if (Boolean.TRUE.equals(c.mainStream())) continuation = continuation.add(new BigDecimal("2"));
        if (!Boolean.TRUE.equals(c.entryTooExtended()) && !Boolean.TRUE.equals(c.falseBreakout())) {
            continuation = continuation.add(new BigDecimal("1"));
        }

        StrategyType primary = StrategyType.UNKNOWN;
        BigDecimal best = BigDecimal.ZERO;
        if (breakout.compareTo(best) > 0) {
            primary = StrategyType.BREAKOUT;
            best = breakout;
        }
        if (pullback.compareTo(best) > 0) {
            primary = StrategyType.PULLBACK;
            best = pullback;
        }
        if (continuation.compareTo(best) > 0) {
            primary = StrategyType.MOMENTUM_CONTINUATION;
        }

        return new StrategyGateResult(primary, breakout, pullback, continuation,
                "classified by price/volume/entry features", "CLASSIFIED", null, null, null);
    }
}
