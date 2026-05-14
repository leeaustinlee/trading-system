package com.austin.trading.engine;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Component
public class ShadowExitRuleEngine {

    public ShadowExitResult evaluate(Input in) {
        List<String> gaps = new ArrayList<>();
        if (in == null || in.currentPrice() == null) {
            return ShadowExitResult.dataGap("DATA_GAP: current price missing");
        }
        RuleSignal trailing = signalByStop(in.currentPrice(), in.trailingStop(), "TRAILING_STOP", gaps);
        RuleSignal ma5 = signalByStop(in.currentPrice(), in.ma5(), "MA5_BREAK", gaps);
        RuleSignal ma10 = signalByStop(in.currentPrice(), in.ma10(), "MA10_BREAK", gaps);
        RuleSignal prevLow = signalByStop(in.currentPrice(), in.previousLow(), "PREVIOUS_LOW_BREAK", gaps);

        BigDecimal atrStop = null;
        if (in.entryPrice() != null && in.atr() != null) {
            atrStop = in.entryPrice().subtract(in.atr().multiply(new BigDecimal("2")))
                    .setScale(4, RoundingMode.HALF_UP);
        } else {
            gaps.add("DATA_GAP: ATR stop requires entry and ATR");
        }
        RuleSignal atr = signalByStop(in.currentPrice(), atrStop, "ATR_STOP", gaps);

        BigDecimal hybridStop = maxNonNull(in.trailingStop(), in.ma10(), in.previousLow(), atrStop);
        RuleSignal hybrid = signalByStop(in.currentPrice(), hybridStop, "HYBRID_STOP", gaps);
        return new ShadowExitResult(trailing, ma5, ma10, prevLow, atr, hybrid, gaps.stream().distinct().toList());
    }

    private RuleSignal signalByStop(BigDecimal current, BigDecimal stop, String action, List<String> gaps) {
        if (stop == null) {
            gaps.add("DATA_GAP: " + action + " price missing");
            return new RuleSignal("DATA_GAP", null);
        }
        return current.compareTo(stop) <= 0 ? new RuleSignal(action, stop) : new RuleSignal("HOLD", stop);
    }

    private BigDecimal maxNonNull(BigDecimal... values) {
        BigDecimal max = null;
        for (BigDecimal v : values) {
            if (v != null && (max == null || v.compareTo(max) > 0)) max = v;
        }
        return max;
    }

    public record Input(
            BigDecimal entryPrice,
            BigDecimal currentPrice,
            BigDecimal trailingStop,
            BigDecimal ma5,
            BigDecimal ma10,
            BigDecimal previousLow,
            BigDecimal atr
    ) {}

    public record RuleSignal(String action, BigDecimal price) {}

    public record ShadowExitResult(
            RuleSignal trailing,
            RuleSignal ma5,
            RuleSignal ma10,
            RuleSignal previousLow,
            RuleSignal atr,
            RuleSignal hybrid,
            List<String> dataGaps
    ) {
        static ShadowExitResult dataGap(String gap) {
            RuleSignal dg = new RuleSignal("DATA_GAP", null);
            return new ShadowExitResult(dg, dg, dg, dg, dg, dg, List.of(gap));
        }
    }
}
