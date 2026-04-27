package com.austin.trading.engine;

import com.austin.trading.entity.PaperTradeEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Paper Trade KPI 計算(pure Java, 無 Spring/DB 依賴)。
 *
 * <p>針對「已平倉」清單算:
 * win_rate / avg_return / median_return / profit_factor / max_drawdown / sharpe(daily) /
 * 依 strategy / theme / source 分組指標。</p>
 *
 * <p>Sharpe 簡化版:把每筆 trade 的日均報酬(pnl_pct / holding_days)當作日序列,
 * 用 {@code sqrt(252) × mean / stddev} 年化。樣本數 < 5 時回 null。</p>
 */
@Component
public class PaperTradeKpiEngine {

    private static final int TRADING_DAYS_PER_YEAR = 252;

    public KpiSnapshot compute(List<PaperTradeEntity> closed) {
        if (closed == null || closed.isEmpty()) return KpiSnapshot.empty();

        List<PaperTradeEntity> trades = closed.stream()
                .filter(p -> p.getPnlPct() != null)
                .sorted(Comparator.comparing(PaperTradeEntity::getExitDate,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (trades.isEmpty()) return KpiSnapshot.empty();

        int total = trades.size();
        int wins = 0, losses = 0, breakEven = 0;
        double sumPnl = 0, sumWin = 0, sumLoss = 0;
        List<Double> pnlList = new ArrayList<>();
        List<Double> dailyReturnList = new ArrayList<>();
        double avgHoldDays = 0;

        for (PaperTradeEntity t : trades) {
            double p = t.getPnlPct().doubleValue();
            pnlList.add(p);
            sumPnl += p;
            if      (p > 0.01)  { wins++;   sumWin  += p; }
            else if (p < -0.01) { losses++; sumLoss += p; }
            else                { breakEven++; }
            int days = t.getHoldingDays() != null && t.getHoldingDays() > 0 ? t.getHoldingDays() : 1;
            avgHoldDays += days;
            dailyReturnList.add(p / days);
        }
        avgHoldDays /= total;
        double avgReturn = sumPnl / total;
        double medianReturn = median(pnlList);
        double profitFactor = (Math.abs(sumLoss) < 1e-9) ? Double.NaN : (sumWin / Math.abs(sumLoss));
        double winRate = (double) wins / total;
        double avgWin = wins == 0 ? 0 : sumWin / wins;
        double avgLoss = losses == 0 ? 0 : sumLoss / losses;
        double maxDrawdown = computeMaxDrawdown(pnlList);
        Double sharpe = computeSharpe(dailyReturnList);
        Double sortino = computeSortino(dailyReturnList);

        Map<String, GroupStats> byStrategy = groupBy(trades, PaperTradeEntity::getStrategyType);
        Map<String, GroupStats> bySource   = groupBy(trades, PaperTradeEntity::getSource);
        Map<String, GroupStats> byTheme    = groupBy(trades, PaperTradeEntity::getThemeTag);

        return new KpiSnapshot(
                total, wins, losses, breakEven,
                round4(winRate), round4(avgReturn), round4(medianReturn),
                round4(avgWin), round4(avgLoss),
                Double.isFinite(profitFactor) ? round4(profitFactor) : null,
                round4(maxDrawdown),
                sharpe == null ? null : round4(sharpe),
                sortino == null ? null : round4(sortino),
                round4(avgHoldDays),
                byStrategy, bySource, byTheme
        );
    }

    private Map<String, GroupStats> groupBy(List<PaperTradeEntity> trades,
                                              java.util.function.Function<PaperTradeEntity, String> keyFn) {
        Map<String, List<PaperTradeEntity>> grouped = new HashMap<>();
        for (PaperTradeEntity t : trades) {
            String k = keyFn.apply(t);
            if (k == null || k.isBlank()) k = "UNKNOWN";
            grouped.computeIfAbsent(k, x -> new ArrayList<>()).add(t);
        }
        Map<String, GroupStats> out = new HashMap<>();
        for (var e : grouped.entrySet()) {
            List<PaperTradeEntity> list = e.getValue();
            int win = 0; double sum = 0;
            for (PaperTradeEntity t : list) {
                double p = t.getPnlPct().doubleValue();
                sum += p;
                if (p > 0.01) win++;
            }
            int n = list.size();
            out.put(e.getKey(), new GroupStats(n, round4((double) win / n), round4(sum / n)));
        }
        return out;
    }

    private double median(List<Double> values) {
        if (values.isEmpty()) return 0;
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private double computeMaxDrawdown(List<Double> pnlPctList) {
        // 把 pnl% 視為 sequential 報酬,計算累積權益曲線最大回撤
        double equity = 100.0, peak = 100.0, maxDd = 0.0;
        for (double p : pnlPctList) {
            equity *= (1.0 + p / 100.0);
            if (equity > peak) peak = equity;
            double dd = (peak - equity) / peak * 100.0;
            if (dd > maxDd) maxDd = dd;
        }
        return maxDd;
    }

    private Double computeSharpe(List<Double> dailyReturnList) {
        if (dailyReturnList.size() < 5) return null;
        double mean = 0;
        for (double r : dailyReturnList) mean += r;
        mean /= dailyReturnList.size();
        double sumSq = 0;
        for (double r : dailyReturnList) sumSq += (r - mean) * (r - mean);
        double std = Math.sqrt(sumSq / (dailyReturnList.size() - 1));
        if (std < 1e-9) return null;
        return Math.sqrt(TRADING_DAYS_PER_YEAR) * mean / std;
    }

    private Double computeSortino(List<Double> dailyReturnList) {
        if (dailyReturnList.size() < 5) return null;
        double mean = 0;
        for (double r : dailyReturnList) mean += r;
        mean /= dailyReturnList.size();
        double sumSqDown = 0;
        int downCount = 0;
        for (double r : dailyReturnList) {
            if (r < 0) {
                sumSqDown += r * r;
                downCount++;
            }
        }
        if (downCount < 2) return null;
        double downStd = Math.sqrt(sumSqDown / downCount);
        if (downStd < 1e-9) return null;
        return Math.sqrt(TRADING_DAYS_PER_YEAR) * mean / downStd;
    }

    private static Double round4(Double v) {
        if (v == null || !Double.isFinite(v)) return null;
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static Double round4(double v) {
        if (!Double.isFinite(v)) return null;
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    public record GroupStats(int count, double winRate, double avgReturn) {}

    public record KpiSnapshot(
            int total, int wins, int losses, int breakEven,
            Double winRate, Double avgReturn, Double medianReturn,
            Double avgWin, Double avgLoss, Double profitFactor,
            Double maxDrawdown, Double sharpe, Double sortino,
            Double avgHoldingDays,
            Map<String, GroupStats> byStrategy,
            Map<String, GroupStats> bySource,
            Map<String, GroupStats> byTheme
    ) {
        public static KpiSnapshot empty() {
            return new KpiSnapshot(0, 0, 0, 0, null, null, null, null, null, null, null, null, null, null,
                    Map.of(), Map.of(), Map.of());
        }
    }
}
