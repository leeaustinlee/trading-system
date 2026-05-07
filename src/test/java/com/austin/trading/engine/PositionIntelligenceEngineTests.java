package com.austin.trading.engine;

import com.austin.trading.domain.enums.HoldDecision;
import com.austin.trading.domain.enums.PositionStrength;
import com.austin.trading.entity.PositionEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PositionIntelligenceEngineTests {
    private final PositionIntelligenceEngine engine = new PositionIntelligenceEngine();

    @Test
    void strongProfitablePositionIsHoldOrHighHold() {
        PositionEntity p = position("2303", "73.9", "91.4", "80.0", "85.0");
        p.setReviewStatus("STRONG");

        var result = engine.evaluatePosition(p);

        assertThat(result.strength()).isEqualTo(PositionStrength.STRONG);
        assertThat(result.holdDecision()).isIn(HoldDecision.HIGH_HOLD, HoldDecision.HOLD);
    }

    @Test
    void weakPositionBelowStopIsExit() {
        PositionEntity p = position("6770", "57.7", "54.0", "56.0", "65.0");
        p.setTrailingStopPrice(new BigDecimal("55.0"));

        var result = engine.evaluatePosition(p);

        assertThat(result.strength()).isEqualTo(PositionStrength.WEAK);
        assertThat(result.holdDecision()).isEqualTo(HoldDecision.EXIT);
    }

    @Test
    void trailingStopOnlyRaisesNeverLowers() {
        PositionEntity p = position("00631L", "22.8", "31.9", "30.5", "35.0");
        p.setTrailingStopPrice(new BigDecimal("31.5"));

        var result = engine.evaluatePosition(p);

        assertThat(result.suggestedStop()).isGreaterThanOrEqualTo(new BigDecimal("31.5000"));
    }

    private PositionEntity position(String symbol, String avgCost, String close, String stop, String tp2) {
        PositionEntity p = new PositionEntity();
        p.setSymbol(symbol);
        p.setStockName(symbol + "Name");
        p.setStatus("OPEN");
        p.setQty(new BigDecimal("1000"));
        p.setAvgCost(new BigDecimal(avgCost));
        p.setClosePrice(new BigDecimal(close));
        p.setStopLossPrice(new BigDecimal(stop));
        p.setTakeProfit2(new BigDecimal(tp2));
        return p;
    }
}
