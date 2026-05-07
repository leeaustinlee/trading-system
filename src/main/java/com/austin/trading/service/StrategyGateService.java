package com.austin.trading.service;

import com.austin.trading.domain.enums.StrategyType;
import com.austin.trading.dto.internal.StrategyGateResult;
import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import com.austin.trading.engine.BreakoutGate;
import com.austin.trading.engine.ContinuationGate;
import com.austin.trading.engine.PullbackGate;
import com.austin.trading.engine.StrategyClassifier;
import org.springframework.stereotype.Service;

@Service
public class StrategyGateService {
    private final StrategyClassifier classifier;
    private final BreakoutGate breakoutGate;
    private final PullbackGate pullbackGate;
    private final ContinuationGate continuationGate;

    public StrategyGateService(StrategyClassifier classifier, BreakoutGate breakoutGate,
                               PullbackGate pullbackGate, ContinuationGate continuationGate) {
        this.classifier = classifier;
        this.breakoutGate = breakoutGate;
        this.pullbackGate = pullbackGate;
        this.continuationGate = continuationGate;
    }

    public FinalDecisionCandidateRequest apply(FinalDecisionCandidateRequest c) {
        StrategyGateResult classified = classifier.classify(c);
        StrategyGateResult gated = switch (classified.primaryStrategy()) {
            case BREAKOUT -> breakoutGate.evaluate(c, classified);
            case PULLBACK -> pullbackGate.evaluate(c, classified);
            case MOMENTUM_CONTINUATION -> continuationGate.evaluate(c, classified);
            case SETUP, MOMENTUM_CHASE, UNKNOWN -> classified;
        };
        StrategyType primary = gated.primaryStrategy();
        return c.withStrategyTrace(primary.name(), primary.name(), gated.breakoutScore(),
                gated.pullbackScore(), gated.continuationScore(), gated.strategyReason(),
                gated.gateStatus(), gated.rejectReason(), gated.entryMode(), gated.riskMode());
    }
}
