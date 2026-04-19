package com.austin.trading.engine;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 回測績效指標計算引擎。
 *
 * <p>從交易列表計算：winRate, avgReturn, maxDrawdown, profitFactor 等。</p>
 */
@Component
public class BacktestMetricsEngine {

    public record BacktestTradeInput(
            BigDecimal pnlPct,
            int holdingDays,
            BigDecimal mfePct,
            BigDecimal maePct,
            String entryTriggerType
    ) {}

    public record BacktestMetricsResult(
            int totalTrades, int winCount, int lossCount,
            BigDecimal winRate, BigDecimal avgReturnPct,
            BigDecimal avgHoldingDays, BigDecimal maxDrawdownPct,
            BigDecimal profitFactor,
            BigDecimal bestTradePct, BigDecimal worstTradePct,
            BigDecimal totalPnl
    ) {}

    public BacktestMetricsResult compute(List<BacktestTradeInput> trades) {
        if (trades == null || trades.isEmpty()) {
            return new BacktestMetricsResult(0, 0, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        int total = trades.size();
        int wins = 0, losses = 0;
        BigDecimal sumReturn = BigDecimal.ZERO;
        BigDecimal sumDays = BigDecimal.ZERO;
        BigDecimal grossProfit = BigDecimal.ZERO;
        BigDecimal grossLoss = BigDecimal.ZERO;
        BigDecimal best = null, worst = null;
        BigDecimal equity = BigDecimal.ZERO;
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDD = BigDecimal.ZERO;

        for (BacktestTradeInput t : trades) {
            BigDecimal pnl = t.pnlPct() != null ? t.pnlPct() : BigDecimal.ZERO;

            if (pnl.signum() > 0) {
                wins++;
                grossProfit = grossProfit.add(pnl);
            } else if (pnl.signum() < 0) {
                losses++;
                grossLoss = grossLoss.add(pnl.abs());
            }

            sumReturn = sumReturn.add(pnl);
            sumDays = sumDays.add(new BigDecimal(t.holdingDays()));

            if (best == null || pnl.compareTo(best) > 0) best = pnl;
            if (worst == null || pnl.compareTo(worst) < 0) worst = pnl;

            // drawdown tracking (equity curve simulation)
            equity = equity.add(pnl);
            if (equity.compareTo(peak) > 0) peak = equity;
            BigDecimal dd = peak.subtract(equity);
            if (dd.compareTo(maxDD) > 0) maxDD = dd;
        }

        BigDecimal totalBd = new BigDecimal(total);
        BigDecimal winRate = total > 0
                ? new BigDecimal(wins).divide(totalBd, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;
        BigDecimal avgReturn = sumReturn.divide(totalBd, 4, RoundingMode.HALF_UP);
        BigDecimal avgDays = sumDays.divide(totalBd, 2, RoundingMode.HALF_UP);
        BigDecimal pf = grossLoss.signum() > 0
                ? grossProfit.divide(grossLoss, 4, RoundingMode.HALF_UP)
                : grossProfit.signum() > 0 ? new BigDecimal("999") : BigDecimal.ZERO;

        return new BacktestMetricsResult(
                total, wins, losses,
                winRate, avgReturn, avgDays, maxDD,
                pf,
                best != null ? best : BigDecimal.ZERO,
                worst != null ? worst : BigDecimal.ZERO,
                sumReturn);
    }
}
