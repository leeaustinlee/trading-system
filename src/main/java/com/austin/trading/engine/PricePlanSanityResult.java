package com.austin.trading.engine;

import java.math.BigDecimal;
import java.util.List;

public record PricePlanSanityResult(
        boolean enabled,
        boolean shadowOnly,
        boolean accepted,
        BigDecimal rrRatio,
        List<PricePlanViolation> violations,
        List<String> rejectedReasons
) {
    public String status() {
        if (!enabled) return "DISABLED";
        return accepted ? "PASS" : (shadowOnly ? "SHADOW_REJECT" : "REJECT");
    }
}
