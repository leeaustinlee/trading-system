package com.austin.trading.engine;

import com.austin.trading.engine.BacktestMetricsEngine.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BacktestMetricsEngineTests {

    private final BacktestMetricsEngine engine = new BacktestMetricsEngine();

    @Test
    void emptyTrades_shouldReturnZeros() {
        var result = engine.compute(List.of());
        assertThat(result.totalTrades()).isZero();
        assertThat(result.winRate()).isEqualByComparingTo("0");
    }

    @Test
    void mixedTrades_shouldComputeCorrectMetrics() {
        var trades = List.of(
                new BacktestTradeInput(bd("5.0"),  3, bd("7.0"),  bd("-1.0"), "BREAKOUT"),
                new BacktestTradeInput(bd("-3.0"), 5, bd("1.0"),  bd("-4.0"), "PULLBACK"),
                new BacktestTradeInput(bd("8.0"),  4, bd("10.0"), bd("-0.5"), "BREAKOUT"),
                new BacktestTradeInput(bd("-2.0"), 7, bd("2.0"),  bd("-3.0"), "REVERSAL")
        );
        var result = engine.compute(trades);

        assertThat(result.totalTrades()).isEqualTo(4);
        assertThat(result.winCount()).isEqualTo(2);
        assertThat(result.lossCount()).isEqualTo(2);
        assertThat(result.winRate()).isEqualByComparingTo("50.0000");
        assertThat(result.bestTradePct()).isEqualByComparingTo("8.0");
        assertThat(result.worstTradePct()).isEqualByComparingTo("-3.0");
        assertThat(result.totalPnl()).isEqualByComparingTo("8.0"); // 5-3+8-2=8
        assertThat(result.profitFactor().doubleValue()).isGreaterThan(1.0); // 13/5=2.6
        assertThat(result.maxDrawdownPct().doubleValue()).isGreaterThan(0);
    }

    @Test
    void allWins_shouldHaveHighProfitFactor() {
        var trades = List.of(
                new BacktestTradeInput(bd("5.0"), 3, bd("6.0"), bd("-0.5"), "BREAKOUT"),
                new BacktestTradeInput(bd("3.0"), 4, bd("4.0"), bd("-1.0"), "PULLBACK")
        );
        var result = engine.compute(trades);
        assertThat(result.winRate()).isEqualByComparingTo("100.0000");
        assertThat(result.profitFactor()).isEqualByComparingTo("999");
    }

    private static BigDecimal bd(String s) { return new BigDecimal(s); }
}
