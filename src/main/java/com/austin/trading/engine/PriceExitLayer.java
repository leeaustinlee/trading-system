package com.austin.trading.engine;

import com.austin.trading.domain.enums.PriceExitState;
import com.austin.trading.engine.PositionDecisionEngine.PositionStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
public class PriceExitLayer {
    public LayerResult evaluate(ExitArbiterInput in) {
        List<String> gaps = new ArrayList<>();
        if (in == null || in.currentPrice() == null) {
            gaps.add("DATA_GAP: current price missing for price layer");
            return new LayerResult(PriceExitState.DATA_GAP, false, gaps, "price data gap");
        }
        BigDecimal p = in.currentPrice();
        if (in.hardStopPrice() != null && p.compareTo(in.hardStopPrice()) <= 0)
            return new LayerResult(PriceExitState.HARD_STOP_BREACH, true, gaps, "hard stop breached");
        if (Boolean.TRUE.equals(in.momentumExitSignal()) || (in.sourceDecision() != null && in.sourceDecision().status() == PositionStatus.EXIT && contains(in.sourceDecision().reason(), "MOMENTUM")))
            return new LayerResult(PriceExitState.MOMENTUM_EXIT_SIGNAL, false, gaps, "momentum exit source signal");
        if (in.previousLow() != null && p.compareTo(in.previousLow()) < 0)
            return new LayerResult(PriceExitState.PREVIOUS_LOW_BREAK, false, gaps, "previous low break");
        if (in.trailingStopPrice() != null && p.compareTo(in.trailingStopPrice()) <= 0)
            return new LayerResult(PriceExitState.TRAILING_STOP_TOUCH, false, gaps, "trailing stop touched");
        if (in.dynamicStopPrice() != null && p.compareTo(in.dynamicStopPrice()) <= 0)
            return new LayerResult(PriceExitState.EFFECTIVE_STOP_TOUCH, false, gaps, "effective/dynamic stop touched");
        if (in.drawdownPct() != null && in.drawdownPct().compareTo(new BigDecimal("7.0")) >= 0)
            return new LayerResult(PriceExitState.DRAWDOWN_THRESHOLD_TOUCH, false, gaps, "drawdown threshold touched");
        return new LayerResult(PriceExitState.NO_TRIGGER, false, gaps, "no price trigger");
    }
    private boolean contains(String s, String needle) { return s != null && s.toUpperCase().contains(needle); }
    public record LayerResult(PriceExitState state, boolean hardRisk, List<String> dataGaps, String reason) {}
}
