package com.austin.trading.service;

import com.austin.trading.dto.response.PaperTradeStatsResponse;
import com.austin.trading.dto.response.PaperTradeStatsResponse.GroupStats;
import com.austin.trading.dto.response.RecentPaperTradeItem;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.repository.PaperTradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Subagent D — Paper Trade aggregated statistics.
 *
 * <p>Public entry {@link #computeStats(int)} aggregates CLOSED paper trades whose
 * {@code exit_date >= today - N} into:</p>
 * <ul>
 *   <li>winRate / avgWinPct / avgLossPct / profitFactor</li>
 *   <li>totalPnlPct / totalPnlAmount / maxDrawdownPct (cumulative compounded equity)</li>
 *   <li>sharpeRatio: raw mean(pnl_pct)/stddev * sqrt(252/avgHoldDays); 0 when n&lt;2 or stddev=0</li>
 *   <li>avgHoldDays / medianHoldDays</li>
 *   <li>byGrade / byTheme / byRegime / byExitReason groupings</li>
 * </ul>
 *
 * <p>Grade derivation: until Subagent A adds {@code entry_grade} column we fall back to
 * thresholds on {@code final_rank_score}: A_PLUS &gt;= 8.2, A_NORMAL &gt;= 7.5,
 * B_TRIAL &gt;= 6.5, otherwise UNKNOWN.</p>
 *
 * <p>byRegime: until {@code entry_regime} column lands every row collapses into UNKNOWN.</p>
 *
 * <p>Empty input yields all-zero scalar metrics and empty group maps - never NaN/Infinity.</p>
 */
@Service
public class PaperTradeStatsService {

    private static final Logger log = LoggerFactory.getLogger(PaperTradeStatsService.class);

    private static final int TRADING_DAYS_PER_YEAR = 252;
    private static final int DEFAULT_DAYS = 30;
    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 365;

    private static final BigDecimal GRADE_A_PLUS_THRESHOLD   = new BigDecimal("8.2");
    private static final BigDecimal GRADE_A_NORMAL_THRESHOLD = new BigDecimal("7.5");
    private static final BigDecimal GRADE_B_TRIAL_THRESHOLD  = new BigDecimal("6.5");

    private final PaperTradeRepository repository;

    public PaperTradeStatsService(PaperTradeRepository repository) {
        this.repository = repository;
    }

    // ====================================================================
    // Public API
    // ====================================================================

    @Transactional(readOnly = true)
    public PaperTradeStatsResponse computeStats(int days) {
        int clamped = Math.max(MIN_DAYS, Math.min(MAX_DAYS, days <= 0 ? DEFAULT_DAYS : days));
        LocalDate from = LocalDate.now().minusDays(clamped);
        List<PaperTradeEntity> closed =
                repository.findByStatusAndExitDateGreaterThanEqualOrderByExitDateDescIdDesc("CLOSED", from);
        return computeStatsFromList(closed);
    }

    @Transactional(readOnly = true)
    public List<RecentPaperTradeItem> listRecent(int limit) {
        int n = (limit <= 0) ? 20 : Math.min(limit, 200);
        List<PaperTradeEntity> closed = repository.findByStatusAndExitDateGreaterThanEqualOrderByExitDateDescIdDesc(
                "CLOSED", LocalDate.now().minusDays(365));
        if (closed == null) return List.of();
        List<RecentPaperTradeItem> out = new ArrayList<>(Math.min(n, closed.size()));
        for (int i = 0; i < closed.size() && out.size() < n; i++) {
            PaperTradeEntity p = closed.get(i);
            out.add(new RecentPaperTradeItem(
                    p.getId(), p.getSymbol(), p.getStockName(),
                    deriveGrade(p),
                    p.getEntryDate(), p.getExitDate(),
                    p.getEntryPrice(), p.getExitPrice(),
                    p.getPnlPct(), p.getPnlAmount(),
                    p.getHoldingDays(), p.getExitReason(), p.getThemeTag()
            ));
        }
        return out;
    }

    // ====================================================================
    // Stat math (package-private for unit tests)
    // ====================================================================

    PaperTradeStatsResponse computeStatsFromList(List<PaperTradeEntity> trades) {
        if (trades == null || trades.isEmpty()) {
            return zeros();
        }
        // chronological order (oldest first) for drawdown
        List<PaperTradeEntity> sorted = new ArrayList<>(trades);
        sorted.sort(Comparator.comparing(PaperTradeEntity::getExitDate,
                Comparator.nullsLast(Comparator.naturalOrder())));

        int total = 0, wins = 0, losses = 0, breakEven = 0;
        double sumPnl = 0, sumWin = 0, sumLoss = 0, sumPnlAmt = 0;
        double sumHold = 0;
        List<Double> pnlList = new ArrayList<>();
        List<Integer> holdList = new ArrayList<>();

        for (PaperTradeEntity t : sorted) {
            if (t.getPnlPct() == null) continue;
            double p = t.getPnlPct().doubleValue();
            pnlList.add(p);
            sumPnl += p;
            total++;
            if      (p > 0.01)  { wins++;   sumWin  += p; }
            else if (p < -0.01) { losses++; sumLoss += p; }
            else                { breakEven++; }
            if (t.getPnlAmount() != null) sumPnlAmt += t.getPnlAmount().doubleValue();
            int hd = (t.getHoldingDays() != null && t.getHoldingDays() > 0) ? t.getHoldingDays() : 1;
            holdList.add(hd);
            sumHold += hd;
        }
        if (total == 0) return zeros();

        double winRate = (double) wins / total;
        double avgWin  = wins == 0 ? 0 : sumWin / wins;
        double avgLoss = losses == 0 ? 0 : sumLoss / losses;
        double profitFactor = (Math.abs(sumLoss) < 1e-9) ? 0.0 : (sumWin / Math.abs(sumLoss));
        double avgHold = sumHold / total;
        double medianHold = medianInt(holdList);
        double maxDd  = computeMaxDrawdown(pnlList);
        double sharpe = computeSharpe(pnlList, avgHold);

        Map<String, GroupStats> byGrade      = groupBy(sorted, this::deriveGrade);
        Map<String, GroupStats> byTheme      = groupBy(sorted, p -> safe(p.getThemeTag()));
        Map<String, GroupStats> byRegime     = groupBy(sorted, p -> safe(p.getEntryRegime()));
        Map<String, GroupStats> byExitReason = groupBy(sorted, p -> safe(p.getExitReason()));

        return new PaperTradeStatsResponse(
                total, wins, losses, breakEven,
                bd4(winRate),
                bd4(avgWin),
                bd4(avgLoss),
                bd4(profitFactor),
                bd4(sumPnl),
                bd2(sumPnlAmt),
                bd4(maxDd),
                bd4(sharpe),
                bd2(avgHold),
                bd2(medianHold),
                byGrade, byTheme, byRegime, byExitReason
        );
    }

    static double computeMaxDrawdown(List<Double> pnlPctSeq) {
        if (pnlPctSeq == null || pnlPctSeq.isEmpty()) return 0.0;
        double equity = 100.0, peak = 100.0, maxDd = 0.0;
        for (Double v : pnlPctSeq) {
            if (v == null || !Double.isFinite(v)) continue;
            equity *= (1.0 + v / 100.0);
            if (equity > peak) peak = equity;
            double dd = (peak == 0) ? 0 : (peak - equity) / peak * 100.0;
            if (dd > maxDd) maxDd = dd;
        }
        return Double.isFinite(maxDd) ? maxDd : 0.0;
    }

    /**
     * Sharpe (annualized approximation): raw mean / stddev, scaled by sqrt(252 / avgHoldDays).
     * Returns 0 when N&lt;2, stddev~=0, or non-finite.
     */
    static double computeSharpe(List<Double> pnlPctSeq, double avgHoldDays) {
        if (pnlPctSeq == null || pnlPctSeq.size() < 2) return 0.0;
        int n = pnlPctSeq.size();
        double mean = 0;
        for (Double v : pnlPctSeq) mean += v;
        mean /= n;
        double sumSq = 0;
        for (Double v : pnlPctSeq) {
            double d = v - mean;
            sumSq += d * d;
        }
        double std = Math.sqrt(sumSq / (n - 1));
        if (std < 1e-9) return 0.0;
        double raw = mean / std;
        double hold = (avgHoldDays <= 0) ? 1.0 : avgHoldDays;
        double scale = Math.sqrt((double) TRADING_DAYS_PER_YEAR / hold);
        double res = raw * scale;
        return Double.isFinite(res) ? res : 0.0;
    }

    static double medianInt(List<Integer> values) {
        if (values == null || values.isEmpty()) return 0.0;
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2).doubleValue();
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    Map<String, GroupStats> groupBy(List<PaperTradeEntity> trades,
                                     Function<PaperTradeEntity, String> keyFn) {
        Map<String, List<PaperTradeEntity>> grouped = new LinkedHashMap<>();
        for (PaperTradeEntity t : trades) {
            if (t.getPnlPct() == null) continue;
            String k = keyFn.apply(t);
            if (k == null || k.isBlank()) k = "UNKNOWN";
            grouped.computeIfAbsent(k, x -> new ArrayList<>()).add(t);
        }
        Map<String, GroupStats> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<PaperTradeEntity>> e : grouped.entrySet()) {
            List<PaperTradeEntity> list = e.getValue();
            int win = 0;
            double sum = 0;
            for (PaperTradeEntity t : list) {
                double p = t.getPnlPct().doubleValue();
                sum += p;
                if (p > 0.01) win++;
            }
            int n = list.size();
            double wr = n == 0 ? 0 : (double) win / n;
            double avg = n == 0 ? 0 : sum / n;
            out.put(e.getKey(), new GroupStats(n, bd4(wr), bd4(avg), bd4(sum)));
        }
        return out;
    }

    /**
     * Prefer Subagent A's entry_grade column (snapshotted at decision time);
     * fall back to deriving from final_rank_score for legacy rows.
     */
    String deriveGrade(PaperTradeEntity p) {
        if (p.getEntryGrade() != null && !p.getEntryGrade().isBlank()) return p.getEntryGrade();
        BigDecimal score = p.getFinalRankScore();
        if (score == null) return "UNKNOWN";
        if (score.compareTo(GRADE_A_PLUS_THRESHOLD) >= 0)   return "A_PLUS";
        if (score.compareTo(GRADE_A_NORMAL_THRESHOLD) >= 0) return "A_NORMAL";
        if (score.compareTo(GRADE_B_TRIAL_THRESHOLD) >= 0)  return "B_TRIAL";
        return "UNKNOWN";
    }

    // ====================================================================
    // Helpers
    // ====================================================================

    private static String safe(String s) { return (s == null || s.isBlank()) ? "UNKNOWN" : s; }

    private static BigDecimal bd4(double v) {
        if (!Double.isFinite(v)) return BigDecimal.ZERO.setScale(4);
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal bd2(double v) {
        if (!Double.isFinite(v)) return BigDecimal.ZERO.setScale(2);
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static PaperTradeStatsResponse zeros() {
        BigDecimal z4 = BigDecimal.ZERO.setScale(4);
        BigDecimal z2 = BigDecimal.ZERO.setScale(2);
        return new PaperTradeStatsResponse(
                0, 0, 0, 0,
                z4, z4, z4, z4,
                z4, z2, z4, z4,
                z2, z2,
                Map.of(), Map.of(), Map.of(), Map.of()
        );
    }
}
