package com.austin.trading.engine;

import com.austin.trading.domain.enums.StrategyType;
import com.austin.trading.dto.internal.StrategyGateResult;
import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class PullbackGate {
    public StrategyGateResult evaluate(FinalDecisionCandidateRequest c, StrategyGateResult base) {
        BigDecimal rr = c.riskRewardRatio() == null ? BigDecimal.ZERO : BigDecimal.valueOf(c.riskRewardRatio());
        if (Boolean.TRUE.equals(c.falseBreakout()) || Boolean.TRUE.equals(c.belowPrevClose())) {
            return enrich(base, "REJECT_SUPPORT", "跌破支撐或昨收，回測失敗", null, "BLOCKED");
        }
        if (rr.compareTo(new BigDecimal("1.5")) < 0 || c.entryPriceZone() == null || c.entryPriceZone().isBlank()) {
            return enrich(base, "REJECT_RR_SUPPORT", "pullback requires RR/support entry zone", null, "BLOCKED");
        }
        return enrich(base, "WAIT_PULLBACK", null, "WAIT_PULLBACK", "NORMAL");
    }

    private StrategyGateResult enrich(StrategyGateResult b, String status, String reject, String entryMode, String riskMode) {
        return new StrategyGateResult(StrategyType.PULLBACK, b.breakoutScore(), b.pullbackScore(),
                b.continuationScore(), "pullback gate", status, reject, entryMode, riskMode);
    }
}
