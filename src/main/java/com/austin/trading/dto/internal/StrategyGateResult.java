package com.austin.trading.dto.internal;

import com.austin.trading.domain.enums.StrategyType;

import java.math.BigDecimal;

public record StrategyGateResult(
        StrategyType primaryStrategy,
        BigDecimal breakoutScore,
        BigDecimal pullbackScore,
        BigDecimal continuationScore,
        String strategyReason,
        String gateStatus,
        String rejectReason,
        String entryMode,
        String riskMode
) {
    public boolean rejected() {
        return gateStatus != null && gateStatus.startsWith("REJECT");
    }
}
