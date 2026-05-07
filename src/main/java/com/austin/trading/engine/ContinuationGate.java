package com.austin.trading.engine;

import com.austin.trading.domain.enums.StrategyType;
import com.austin.trading.dto.internal.StrategyGateResult;
import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class ContinuationGate {
    public StrategyGateResult evaluate(FinalDecisionCandidateRequest c, StrategyGateResult base) {
        if (Boolean.TRUE.equals(c.entryTooExtended()) && c.volumeRatio() != null
                && c.volumeRatio().compareTo(new BigDecimal("3.0")) >= 0) {
            return enrich(base, "REJECT_OVERHEATED", "過熱或爆大量長黑", null, "BLOCKED");
        }
        BigDecimal rr = c.riskRewardRatio() == null ? BigDecimal.ZERO : BigDecimal.valueOf(c.riskRewardRatio());
        if (rr.compareTo(new BigDecimal("1.2")) < 0) {
            return enrich(base, "WATCH", "RR below continuation floor", "WATCH", "REDUCED");
        }
        return enrich(base, Boolean.TRUE.equals(c.entryTriggered()) ? "ENTER_SMALL" : "WATCH",
                null, Boolean.TRUE.equals(c.entryTriggered()) ? "ENTER_SMALL" : "WATCH", "REDUCED");
    }

    private StrategyGateResult enrich(StrategyGateResult b, String status, String reject, String entryMode, String riskMode) {
        return new StrategyGateResult(StrategyType.MOMENTUM_CONTINUATION, b.breakoutScore(), b.pullbackScore(),
                b.continuationScore(), "continuation gate", status, reject, entryMode, riskMode);
    }
}
