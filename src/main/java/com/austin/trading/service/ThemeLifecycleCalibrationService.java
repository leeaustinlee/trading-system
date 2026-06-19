package com.austin.trading.service;

import com.austin.trading.dto.response.ThemeLifecycleCalibrationResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ThemeLifecycleCalibrationService {
    private static final int MIN_PREDICTIVE_SAMPLE = 8;
    private static final List<TableSpec> TABLES = List.of(
            new TableSpec("trading_funnel_trace", "trading_date"),
            new TableSpec("theme_admission_shadow_decision", "trading_date"),
            new TableSpec("ranking_topn_shadow_result", "trading_date"),
            new TableSpec("theme_lifecycle_state", "trading_date"),
            new TableSpec("paper_trade", "entry_date"),
            new TableSpec("stop_washout_outcome", "DATE(exit_signal_at)"),
            new TableSpec("portfolio_risk_decision", "trading_date")
    );

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public ThemeLifecycleCalibrationResponse calibration(int days) {
        int requestedDays = Math.max(1, Math.min(days, 365));
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(requestedDays - 1L);
        List<String> dataGaps = new ArrayList<>();
        Map<String, ThemeLifecycleCalibrationResponse.DataCoverage> coverage = dataCoverage(start, dataGaps);

        Set<LocalDate> actualDates = collectActualDates(start, dataGaps);
        LocalDate actualStart = actualDates.stream().min(LocalDate::compareTo).orElse(null);
        LocalDate actualEnd = actualDates.stream().max(LocalDate::compareTo).orElse(null);

        List<ThemeLifecycleCalibrationResponse.StageDistribution> stages = stageDistribution(start, dataGaps);
        ThemeLifecycleCalibrationResponse.FunnelSummary funnel = funnelSummary(start, dataGaps);
        ThemeLifecycleCalibrationResponse.ThemeAdmissionSummary admission = themeAdmissionSummary(start, dataGaps);
        List<ThemeLifecycleCalibrationResponse.TopNShadowBucket> topN = topNShadowSummary(start, dataGaps);
        ThemeLifecycleCalibrationResponse.PredictivePower predictive = predictivePower(start, dataGaps);
        List<ThemeLifecycleCalibrationResponse.CalibrationFinding> findings =
                calibrationFindings(start, stages, predictive, dataGaps);

        return new ThemeLifecycleCalibrationResponse(
                true, true, true, requestedDays, start, end, actualStart, actualEnd, actualDates.size(),
                coverage, stages, funnel, admission, topN, predictive, findings, List.copyOf(dataGaps));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> dataGaps(int days) {
        ThemeLifecycleCalibrationResponse report = calibration(days);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("readOnly", true);
        out.put("advisoryOnly", true);
        out.put("doesNotAffectBuySell", true);
        out.put("requestedDays", report.requestedDays());
        out.put("requestedStartDate", report.requestedStartDate());
        out.put("requestedEndDate", report.requestedEndDate());
        out.put("actualStartDate", report.actualStartDate());
        out.put("actualEndDate", report.actualEndDate());
        out.put("actualAvailableDays", report.actualAvailableDays());
        out.put("dataCoverage", report.dataCoverage());
        out.put("lifecycleMetricPredictivePower", report.lifecycleMetricPredictivePower());
        out.put("calibrationFindings", report.calibrationFindings());
        out.put("dataGaps", report.dataGaps());
        return out;
    }

    private Map<String, ThemeLifecycleCalibrationResponse.DataCoverage> dataCoverage(LocalDate start, List<String> gaps) {
        Map<String, ThemeLifecycleCalibrationResponse.DataCoverage> out = new LinkedHashMap<>();
        for (TableSpec table : TABLES) {
            if (!tableExists(table.name())) {
                String reason = "TABLE_MISSING:" + table.name();
                gaps.add(reason);
                out.put(table.name(), new ThemeLifecycleCalibrationResponse.DataCoverage(
                        table.name(), 0, null, null, 0, false, reason));
                continue;
            }
            try {
                Object[] row = one("""
                        SELECT COUNT(*), MIN(%s), MAX(%s), COUNT(DISTINCT %s)
                        FROM %s
                        WHERE %s >= :start AND %s <= CURRENT_DATE()
                        """.formatted(table.dateExpression(), table.dateExpression(), table.dateExpression(),
                        table.name(), table.dateExpression(), table.dateExpression()), Map.of("start", start));
                long count = longAt(row, 0);
                out.put(table.name(), new ThemeLifecycleCalibrationResponse.DataCoverage(
                        table.name(), count, dateAt(row, 1), dateAt(row, 2), intAt(row, 3), true,
                        count == 0 ? "NO_ROWS_IN_REQUESTED_WINDOW" : null));
                if (count == 0) {
                    gaps.add("NO_ROWS_IN_REQUESTED_WINDOW:" + table.name());
                }
            } catch (RuntimeException ex) {
                String reason = "QUERY_FAILED:" + table.name() + ":" + ex.getClass().getSimpleName();
                gaps.add(reason);
                out.put(table.name(), new ThemeLifecycleCalibrationResponse.DataCoverage(
                        table.name(), 0, null, null, 0, true, reason));
            }
        }
        return out;
    }

    private Set<LocalDate> collectActualDates(LocalDate start, List<String> gaps) {
        Set<LocalDate> dates = new HashSet<>();
        for (TableSpec table : TABLES) {
            if (!tableExists(table.name())) {
                continue;
            }
            try {
                for (Object value : list("""
                        SELECT DISTINCT %s FROM %s WHERE %s >= :start AND %s <= CURRENT_DATE()
                        """.formatted(table.dateExpression(), table.name(), table.dateExpression(), table.dateExpression()), Map.of("start", start))) {
                    LocalDate date = toDate(value);
                    if (date != null) {
                        dates.add(date);
                    }
                }
            } catch (RuntimeException ex) {
                gaps.add("DATE_SCAN_FAILED:" + table.name() + ":" + ex.getClass().getSimpleName());
            }
        }
        return dates;
    }

    private List<ThemeLifecycleCalibrationResponse.StageDistribution> stageDistribution(LocalDate start, List<String> gaps) {
        if (!tableExists("theme_lifecycle_state")) {
            return List.of();
        }
        try {
            return list("""
                    SELECT stage, COUNT(*), AVG(lifecycle_score), AVG(continuation_days), AVG(breadth), AVG(crowding_score)
                    FROM theme_lifecycle_state
                    WHERE trading_date >= :start
                    GROUP BY stage
                    ORDER BY COUNT(*) DESC, stage
                    """, Map.of("start", start)).stream()
                    .map(this::row)
                    .map(r -> new ThemeLifecycleCalibrationResponse.StageDistribution(
                            stringAt(r, 0), longAt(r, 1), decimalAt(r, 2), decimalAt(r, 3),
                            decimalAt(r, 4), decimalAt(r, 5)))
                    .toList();
        } catch (RuntimeException ex) {
            gaps.add("STAGE_DISTRIBUTION_FAILED:" + ex.getClass().getSimpleName());
            return List.of();
        }
    }

    private ThemeLifecycleCalibrationResponse.FunnelSummary funnelSummary(LocalDate start, List<String> gaps) {
        if (!tableExists("trading_funnel_trace")) {
            return ThemeLifecycleCalibrationResponse.FunnelSummary.empty();
        }
        try {
            Object[] row = one("""
                    SELECT
                        COUNT(*),
                        SUM(CASE WHEN candidate_id IS NOT NULL OR UPPER(COALESCE(candidate_status, '')) IN ('PASS','HIT','CREATED','SELECTED','ADMIT') THEN 1 ELSE 0 END),
                        SUM(CASE WHEN watchlist_id IS NOT NULL OR UPPER(COALESCE(watchlist_status, '')) IN ('PASS','HIT','CREATED','SELECTED','WATCH') THEN 1 ELSE 0 END),
                        SUM(CASE WHEN ranking_rank IS NOT NULL OR UPPER(COALESCE(ranking_status, '')) IN ('PASS','HIT','RANKED','SELECTED') THEN 1 ELSE 0 END),
                        SUM(CASE WHEN UPPER(COALESCE(risk_status, '')) IN ('PASS','APPROVED','OK') THEN 1 ELSE 0 END),
                        SUM(CASE WHEN buy_trade_id IS NOT NULL OR UPPER(COALESCE(buy_status, '')) IN ('BUY','BOUGHT','ENTER','EXECUTED','PASS') THEN 1 ELSE 0 END)
                    FROM trading_funnel_trace
                    WHERE trading_date >= :start
                    """, Map.of("start", start));
            long hot = longAt(row, 0);
            long candidate = longAt(row, 1);
            long watchlist = longAt(row, 2);
            long ranking = longAt(row, 3);
            long risk = longAt(row, 4);
            long buy = longAt(row, 5);
            Map<String, BigDecimal> rates = new LinkedHashMap<>();
            rates.put("candidateFromSignal", rate(candidate, hot));
            rates.put("watchlistFromSignal", rate(watchlist, hot));
            rates.put("rankingFromSignal", rate(ranking, hot));
            rates.put("riskPassFromSignal", rate(risk, hot));
            rates.put("buyFromSignal", rate(buy, hot));
            rates.put("buyFromRiskPass", rate(buy, risk));
            return new ThemeLifecycleCalibrationResponse.FunnelSummary(
                    hot, candidate, watchlist, ranking, risk, buy, rates,
                    groupedCounts("trading_funnel_trace", "blocked_stage", "trading_date", start, 20, gaps),
                    groupedCounts("trading_funnel_trace", "blocked_reason", "trading_date", start, 10, gaps));
        } catch (RuntimeException ex) {
            gaps.add("FUNNEL_SUMMARY_FAILED:" + ex.getClass().getSimpleName());
            return ThemeLifecycleCalibrationResponse.FunnelSummary.empty();
        }
    }

    private ThemeLifecycleCalibrationResponse.ThemeAdmissionSummary themeAdmissionSummary(LocalDate start, List<String> gaps) {
        if (!tableExists("theme_admission_shadow_decision")) {
            return ThemeLifecycleCalibrationResponse.ThemeAdmissionSummary.empty();
        }
        try {
            Object[] row = one("""
                    SELECT
                        SUM(CASE WHEN would_write_candidate = TRUE THEN 1 ELSE 0 END),
                        SUM(CASE WHEN would_write_watchlist = TRUE THEN 1 ELSE 0 END),
                        SUM(CASE WHEN would_create_pullback_plan = TRUE THEN 1 ELSE 0 END)
                    FROM theme_admission_shadow_decision
                    WHERE trading_date >= :start
                    """, Map.of("start", start));
            return new ThemeLifecycleCalibrationResponse.ThemeAdmissionSummary(
                    groupedCounts("theme_admission_shadow_decision", "shadow_action", "trading_date", start, 20, gaps),
                    longAt(row, 0), longAt(row, 1), longAt(row, 2));
        } catch (RuntimeException ex) {
            gaps.add("THEME_ADMISSION_SUMMARY_FAILED:" + ex.getClass().getSimpleName());
            return ThemeLifecycleCalibrationResponse.ThemeAdmissionSummary.empty();
        }
    }

    private List<ThemeLifecycleCalibrationResponse.TopNShadowBucket> topNShadowSummary(LocalDate start, List<String> gaps) {
        if (!tableExists("ranking_topn_shadow_result")) {
            return List.of();
        }
        try {
            return list("""
                    SELECT
                        CASE
                            WHEN ranking_rank = 1 THEN 'Top1'
                            WHEN ranking_rank <= 3 THEN 'Top3'
                            WHEN ranking_rank <= 5 THEN 'Top5'
                            WHEN ranking_rank <= 10 THEN 'Top10'
                            WHEN ranking_rank <= 20 THEN 'Top20'
                            ELSE 'Outside20'
                        END AS rank_bucket,
                        COUNT(*),
                        AVG(actual_return_1d),
                        AVG(CASE WHEN actual_return_1d IS NULL THEN NULL WHEN actual_return_1d > 0 THEN 1 ELSE 0 END),
                        AVG(actual_return_5d),
                        AVG(CASE WHEN actual_return_5d IS NULL THEN NULL WHEN actual_return_5d > 0 THEN 1 ELSE 0 END),
                        AVG(actual_return_10d),
                        AVG(CASE WHEN actual_return_10d IS NULL THEN NULL WHEN actual_return_10d > 0 THEN 1 ELSE 0 END),
                        AVG(max_drawdown_10d),
                        SUM(CASE WHEN missed_by_top3 = TRUE THEN 1 ELSE 0 END)
                    FROM ranking_topn_shadow_result
                    WHERE trading_date >= :start
                    GROUP BY rank_bucket
                    ORDER BY CASE rank_bucket WHEN 'Top1' THEN 1 WHEN 'Top3' THEN 2 WHEN 'Top5' THEN 3 WHEN 'Top10' THEN 4 WHEN 'Top20' THEN 5 ELSE 6 END
                    """, Map.of("start", start)).stream()
                    .map(this::row)
                    .map(r -> new ThemeLifecycleCalibrationResponse.TopNShadowBucket(
                            stringAt(r, 0), longAt(r, 1), decimalAt(r, 2), percentAt(r, 3),
                            decimalAt(r, 4), percentAt(r, 5), decimalAt(r, 6), percentAt(r, 7),
                            decimalAt(r, 8), longAt(r, 9)))
                    .toList();
        } catch (RuntimeException ex) {
            gaps.add("TOPN_SHADOW_SUMMARY_FAILED:" + ex.getClass().getSimpleName());
            return List.of();
        }
    }

    private ThemeLifecycleCalibrationResponse.PredictivePower predictivePower(LocalDate start, List<String> gaps) {
        if (!tableExists("paper_trade") || !tableExists("theme_replay_node") || !tableExists("theme_lifecycle_state")) {
            return ThemeLifecycleCalibrationResponse.PredictivePower.insufficient(0, "JOIN_TABLE_MISSING");
        }
        try {
            List<MetricSample> samples = list("""
                    SELECT
                        tls.lifecycle_score,
                        tls.continuation_days,
                        tls.breadth,
                        tls.crowding_score,
                        COALESCE(pt.return_10d, pt.return_5d, pt.return_1d, pt.pnl_pct)
                    FROM paper_trade pt
                    JOIN theme_replay_node trn ON trn.trading_date = pt.entry_date AND trn.symbol = pt.symbol
                    JOIN theme_lifecycle_state tls ON tls.trading_date = trn.trading_date AND tls.theme_tag = trn.theme_tag
                    WHERE pt.entry_date >= :start
                      AND COALESCE(pt.return_10d, pt.return_5d, pt.return_1d, pt.pnl_pct) IS NOT NULL
                    """, Map.of("start", start)).stream()
                    .map(this::row)
                    .map(r -> new MetricSample(doubleAt(r, 0), doubleAt(r, 1), doubleAt(r, 2), doubleAt(r, 3), doubleAt(r, 4)))
                    .filter(MetricSample::hasReturn)
                    .toList();
            if (samples.size() < MIN_PREDICTIVE_SAMPLE) {
                gaps.add("INSUFFICIENT_PREDICTIVE_SAMPLE:n=" + samples.size());
                return ThemeLifecycleCalibrationResponse.PredictivePower.insufficient(samples.size(), "INSUFFICIENT_SAMPLE");
            }
            Spread spread = lifecycleScoreSpread(samples);
            return new ThemeLifecycleCalibrationResponse.PredictivePower(
                    samples.size(),
                    toDecimal(correlation(samples, MetricSample::lifecycleScore)),
                    toDecimal(correlation(samples, MetricSample::continuationDays)),
                    toDecimal(correlation(samples, MetricSample::breadth)),
                    toDecimal(correlation(samples, MetricSample::crowdingScore)),
                    toDecimal(spread.topAverage()),
                    toDecimal(spread.bottomAverage()),
                    toDecimal(spread.spread()),
                    null);
        } catch (RuntimeException ex) {
            gaps.add("PREDICTIVE_POWER_FAILED:" + ex.getClass().getSimpleName());
            return ThemeLifecycleCalibrationResponse.PredictivePower.insufficient(0, "QUERY_FAILED");
        }
    }

    private List<ThemeLifecycleCalibrationResponse.CalibrationFinding> calibrationFindings(
            LocalDate start,
            List<ThemeLifecycleCalibrationResponse.StageDistribution> stages,
            ThemeLifecycleCalibrationResponse.PredictivePower predictive,
            List<String> gaps) {
        List<ThemeLifecycleCalibrationResponse.CalibrationFinding> findings = new ArrayList<>();
        boolean hasMainstream = stages.stream().anyMatch(s -> "MAINSTREAM".equalsIgnoreCase(s.stage()) && s.sampleCount() > 0);
        if (!hasMainstream) {
            findings.add(new ThemeLifecycleCalibrationResponse.CalibrationFinding(
                    "MAINSTREAM_ZERO", "WARN", "No MAINSTREAM lifecycle_state rows found in the requested window."));
        }
        if (tableExists("paper_trade") && tableExists("theme_replay_node") && tableExists("theme_lifecycle_state")) {
            try {
                Object[] row = one("""
                        SELECT COUNT(*), AVG(COALESCE(pt.return_10d, pt.return_5d, pt.return_1d, pt.pnl_pct))
                        FROM paper_trade pt
                        JOIN theme_replay_node trn ON trn.trading_date = pt.entry_date AND trn.symbol = pt.symbol
                        JOIN theme_lifecycle_state tls ON tls.trading_date = trn.trading_date AND tls.theme_tag = trn.theme_tag
                        WHERE pt.entry_date >= :start
                          AND UPPER(tls.stage) = 'OVERHEATED'
                          AND COALESCE(pt.return_10d, pt.return_5d, pt.return_1d, pt.pnl_pct) IS NOT NULL
                        """, Map.of("start", start));
                if (longAt(row, 0) > 0 && doubleAt(row, 1) != null && doubleAt(row, 1) > 0.0) {
                    findings.add(new ThemeLifecycleCalibrationResponse.CalibrationFinding(
                            "OVERHEATED_STRONG_TREND_WARNING", "INFO",
                            "OVERHEATED samples have positive realized returns in available paper_trade data."));
                }
            } catch (RuntimeException ex) {
                gaps.add("OVERHEATED_FINDING_FAILED:" + ex.getClass().getSimpleName());
            }
        }
        portfolioDateWarning(start).ifPresent(findings::add);
        if (predictive.dataGapReason() != null) {
            findings.add(new ThemeLifecycleCalibrationResponse.CalibrationFinding(
                    "PREDICTIVE_POWER_DATA_GAP", "INFO", predictive.dataGapReason()));
        }
        return findings;
    }

    private java.util.Optional<ThemeLifecycleCalibrationResponse.CalibrationFinding> portfolioDateWarning(LocalDate start) {
        if (!tableExists("portfolio_risk_decision")) {
            return java.util.Optional.empty();
        }
        try {
            Object[] row = one("""
                    SELECT COUNT(*), MIN(trading_date), MAX(trading_date)
                    FROM portfolio_risk_decision
                    WHERE trading_date >= :start AND trading_date > CURRENT_DATE()
                    """, Map.of("start", start));
            long count = longAt(row, 0);
            if (count > 0) {
                return java.util.Optional.of(new ThemeLifecycleCalibrationResponse.CalibrationFinding(
                        "PORTFOLIO_DATA_DATE_WARNING", "WARN",
                        "portfolio_risk_decision contains future-dated rows: count=" + count
                                + ", first=" + dateAt(row, 1) + ", last=" + dateAt(row, 2)));
            }
        } catch (RuntimeException ignored) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.empty();
    }

    private Map<String, Long> groupedCounts(String table, String column, String dateColumn, LocalDate start, int limit, List<String> gaps) {
        try {
            Map<String, Long> out = new LinkedHashMap<>();
            Query q = entityManager.createNativeQuery("""
                    SELECT COALESCE(%s, 'UNKNOWN'), COUNT(*)
                    FROM %s
                    WHERE %s >= :start
                    GROUP BY COALESCE(%s, 'UNKNOWN')
                    ORDER BY COUNT(*) DESC
                    """.formatted(column, table, dateColumn, column));
            q.setParameter("start", start);
            q.setMaxResults(limit);
            for (Object result : q.getResultList()) {
                Object[] r = row(result);
                out.put(stringAt(r, 0), longAt(r, 1));
            }
            return out;
        } catch (RuntimeException ex) {
            gaps.add("GROUPED_COUNT_FAILED:" + table + "." + column + ":" + ex.getClass().getSimpleName());
            return Map.of();
        }
    }

    private boolean tableExists(String tableName) {
        try {
            Object count = entityManager.createNativeQuery("""
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = DATABASE() AND table_name = :tableName
                    """)
                    .setParameter("tableName", tableName)
                    .getSingleResult();
            return toLong(count) > 0;
        } catch (RuntimeException ex) {
            try {
                entityManager.createNativeQuery("SELECT 1 FROM " + tableName + " WHERE 1 = 0").getResultList();
                return true;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
    }

    private Object[] one(String sql, Map<String, ?> params) {
        Object result = query(sql, params).getSingleResult();
        return row(result);
    }

    private List<?> list(String sql, Map<String, ?> params) {
        return query(sql, params).getResultList();
    }

    private Query query(String sql, Map<String, ?> params) {
        Query q = entityManager.createNativeQuery(sql);
        params.forEach(q::setParameter);
        return q;
    }

    private Object[] row(Object value) {
        return value instanceof Object[] arr ? arr : new Object[]{value};
    }

    static BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(4);
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    static Double correlation(List<MetricSample> samples, MetricExtractor extractor) {
        List<double[]> values = new ArrayList<>();
        for (MetricSample sample : samples) {
            Double x = extractor.value(sample);
            Double y = sample.realizedReturn();
            if (x != null && y != null && !x.isNaN() && !y.isNaN()) {
                values.add(new double[]{x, y});
            }
        }
        if (values.size() < MIN_PREDICTIVE_SAMPLE) {
            return null;
        }
        double avgX = values.stream().mapToDouble(v -> v[0]).average().orElse(Double.NaN);
        double avgY = values.stream().mapToDouble(v -> v[1]).average().orElse(Double.NaN);
        double num = 0.0;
        double denX = 0.0;
        double denY = 0.0;
        for (double[] v : values) {
            double dx = v[0] - avgX;
            double dy = v[1] - avgY;
            num += dx * dy;
            denX += dx * dx;
            denY += dy * dy;
        }
        if (denX == 0.0 || denY == 0.0) {
            return null;
        }
        return num / Math.sqrt(denX * denY);
    }

    static Spread lifecycleScoreSpread(List<MetricSample> samples) {
        List<MetricSample> ordered = samples.stream()
                .filter(s -> s.lifecycleScore() != null && s.realizedReturn() != null)
                .filter(s -> !s.lifecycleScore().isNaN() && !s.realizedReturn().isNaN())
                .sorted(Comparator.comparingDouble(MetricSample::lifecycleScore))
                .toList();
        if (ordered.size() < MIN_PREDICTIVE_SAMPLE) {
            return new Spread(Double.NaN, Double.NaN, Double.NaN);
        }
        int quartile = Math.max(1, ordered.size() / 4);
        double bottom = ordered.subList(0, quartile).stream().mapToDouble(MetricSample::realizedReturn).average().orElse(Double.NaN);
        double top = ordered.subList(ordered.size() - quartile, ordered.size()).stream().mapToDouble(MetricSample::realizedReturn).average().orElse(Double.NaN);
        return new Spread(top, bottom, top - bottom);
    }

    private BigDecimal toDecimal(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal percentAt(Object[] row, int index) {
        BigDecimal decimal = decimalAt(row, index);
        return decimal == null ? null : decimal.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal decimalAt(Object[] row, int index) {
        if (row.length <= index || row[index] == null) {
            return null;
        }
        if (row[index] instanceof BigDecimal bd) {
            return bd.setScale(4, RoundingMode.HALF_UP);
        }
        if (row[index] instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue()).setScale(4, RoundingMode.HALF_UP);
        }
        return new BigDecimal(row[index].toString()).setScale(4, RoundingMode.HALF_UP);
    }

    private Double doubleAt(Object[] row, int index) {
        if (row.length <= index || row[index] == null) {
            return null;
        }
        if (row[index] instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(row[index].toString());
    }

    private long longAt(Object[] row, int index) {
        if (row.length <= index || row[index] == null) {
            return 0L;
        }
        return toLong(row[index]);
    }

    private int intAt(Object[] row, int index) {
        return Math.toIntExact(longAt(row, index));
    }

    private long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private String stringAt(Object[] row, int index) {
        if (row.length <= index || row[index] == null) {
            return "UNKNOWN";
        }
        String value = row[index].toString();
        return value.isBlank() ? "UNKNOWN" : value;
    }

    private LocalDate dateAt(Object[] row, int index) {
        if (row.length <= index) {
            return null;
        }
        return toDate(row[index]);
    }

    private LocalDate toDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate d) {
            return d;
        }
        if (value instanceof Date d) {
            return d.toLocalDate();
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().toLocalDate();
        }
        if (value instanceof LocalDateTime dt) {
            return dt.toLocalDate();
        }
        return LocalDate.parse(value.toString().substring(0, 10));
    }

    private record TableSpec(String name, String dateExpression) {}

    record MetricSample(Double lifecycleScore, Double continuationDays, Double breadth, Double crowdingScore, Double realizedReturn) {
        boolean hasReturn() {
            return realizedReturn != null && !realizedReturn.isNaN();
        }
    }

    record Spread(Double topAverage, Double bottomAverage, Double spread) {}

    interface MetricExtractor {
        Double value(MetricSample sample);
    }
}
