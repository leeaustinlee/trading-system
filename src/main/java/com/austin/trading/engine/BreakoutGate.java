package com.austin.trading.engine;

import com.austin.trading.domain.enums.StrategyType;
import com.austin.trading.dto.internal.StrategyGateResult;
import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class BreakoutGate {
    public StrategyGateResult evaluate(FinalDecisionCandidateRequest c, StrategyGateResult base) {
        if ("漲幅過大,僅參考".equals(c.tradabilityTag())) {
            return enrich(base, "WATCH_NEXT_DAY", "limit-up or too extended cannot be filled", "WATCH_NEXT_DAY", "REDUCED");
        }
        if (Boolean.TRUE.equals(c.falseBreakout())) {
            return enrich(base, "REJECT", "false breakout", null, "BLOCKED");
        }
        BigDecimal rr = c.riskRewardRatio() == null ? BigDecimal.ZERO : BigDecimal.valueOf(c.riskRewardRatio());
        if (rr.compareTo(new BigDecimal("1.1")) < 0 && !Boolean.TRUE.equals(c.mainStream())) {
            return enrich(base, "REJECT_RR", "breakout RR too low without strong theme", null, "BLOCKED");
        }
        String status = Boolean.TRUE.equals(c.entryTriggered()) ? "ENTER_SMALL" : "WATCH";
        return enrich(base, status, null, status, "REDUCED");
    }

    private StrategyGateResult enrich(StrategyGateResult b, String status, String reject, String entryMode, String riskMode) {
        return new StrategyGateResult(StrategyType.BREAKOUT, b.breakoutScore(), b.pullbackScore(),
                b.continuationScore(), "breakout gate", status, reject, entryMode, riskMode);
    }
}
