package com.austin.trading.engine;

import com.austin.trading.service.ScoreConfigService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Component
public class PricePlanSanityEngine {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final ScoreConfigService config;

    public PricePlanSanityEngine(ScoreConfigService config) {
        this.config = config;
    }

    public PricePlanSanityResult evaluate(Input in) {
        boolean enabled = config == null || config.getBoolean("price_plan.sanity.enabled", true);
        boolean shadowOnly = config == null || config.getBoolean("price_plan.sanity.shadow_only", true);
        if (!enabled) {
            return new PricePlanSanityResult(false, shadowOnly, true, null, List.of(), List.of());
        }

        List<PricePlanViolation> violations = new ArrayList<>();
        BigDecimal entry = in != null ? in.entry() : null;
        BigDecimal stop = in != null ? in.stop() : null;
        BigDecimal tp1 = in != null ? in.tp1() : null;
        BigDecimal tp2 = in != null ? in.tp2() : null;
        String strategyType = in != null ? in.strategyType() : null;

        if (!positive(entry)) violations.add(PricePlanViolation.MISSING_ENTRY);
        if (!positive(stop)) violations.add(PricePlanViolation.MISSING_STOP);
        if (!positive(tp1)) violations.add(PricePlanViolation.MISSING_TP1);
        if (!positive(tp2)) violations.add(PricePlanViolation.MISSING_TP2);
        if (in != null && in.stale()) violations.add(PricePlanViolation.STALE_PRICE_PLAN);

        BigDecimal rr = null;
        if (positive(entry) && positive(stop) && positive(tp1)) {
            if (stop.compareTo(entry) >= 0) violations.add(PricePlanViolation.STOP_NOT_BELOW_ENTRY);
            if (tp1.compareTo(entry) <= 0) violations.add(PricePlanViolation.TP1_NOT_ABOVE_ENTRY);
            BigDecimal risk = entry.subtract(stop);
            BigDecimal reward = tp1.subtract(entry);
            if (risk.signum() > 0) {
                rr = reward.divide(risk, 4, RoundingMode.HALF_UP);
                if (rr.signum() < 0) violations.add(PricePlanViolation.RR_NEGATIVE);
                BigDecimal minRr = minRr(strategyType);
                if (rr.compareTo(minRr) < 0) violations.add(PricePlanViolation.RR_BELOW_THRESHOLD);
            } else {
                violations.add(PricePlanViolation.RR_NEGATIVE);
            }

            BigDecimal tp1GainPct = pct(tp1.subtract(entry), entry);
            BigDecimal minGain = decimal("price_plan.tp1_min_gain_pct", "3.0");
            if (tp1GainPct.compareTo(minGain) < 0) violations.add(PricePlanViolation.TP1_GAIN_TOO_SMALL);

            BigDecimal stopLossPct = pct(entry.subtract(stop), entry);
            if (stopLossPct.compareTo(decimal("price_plan.stop_min_loss_pct", "1.5")) < 0) {
                violations.add(PricePlanViolation.STOP_TOO_CLOSE);
            }
            if (stopLossPct.compareTo(decimal("price_plan.stop_max_loss_pct", "10.0")) > 0) {
                violations.add(PricePlanViolation.STOP_TOO_FAR);
            }
        }

        if (positive(tp1) && positive(tp2) && tp2.compareTo(tp1) <= 0) {
            violations.add(PricePlanViolation.TP2_NOT_ABOVE_TP1);
        }

        List<PricePlanViolation> distinct = violations.stream().distinct().toList();
        return new PricePlanSanityResult(enabled, shadowOnly, distinct.isEmpty(), rr,
                distinct, distinct.stream().map(Enum::name).toList());
    }

    private BigDecimal minRr(String strategyType) {
        if ("MOMENTUM_CHASE".equalsIgnoreCase(strategyType) || "MOMENTUM".equalsIgnoreCase(strategyType)) {
            return decimal("price_plan.min_rr.momentum", "2.0");
        }
        return decimal("price_plan.min_rr.setup", "1.8");
    }

    private BigDecimal decimal(String key, String fallback) {
        return config == null ? new BigDecimal(fallback) : config.getDecimal(key, new BigDecimal(fallback));
    }

    private static boolean positive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    private static BigDecimal pct(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() == 0) return BigDecimal.ZERO;
        return numerator.divide(denominator, 6, RoundingMode.HALF_UP).multiply(HUNDRED)
                .setScale(4, RoundingMode.HALF_UP);
    }

    public record Input(
            BigDecimal entry,
            BigDecimal stop,
            BigDecimal tp1,
            BigDecimal tp2,
            String strategyType,
            boolean stale
    ) {}
}
