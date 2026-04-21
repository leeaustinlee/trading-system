package com.austin.trading.service;

import com.austin.trading.dto.internal.BenchmarkInput;
import com.austin.trading.dto.internal.BenchmarkReport;
import com.austin.trading.dto.internal.TradeAttributionOutput;
import com.austin.trading.engine.BenchmarkAnalyticsEngine;
import com.austin.trading.entity.BenchmarkAnalyticsEntity;
import com.austin.trading.entity.ThemeSnapshotEntity;
import com.austin.trading.repository.BenchmarkAnalyticsRepository;
import com.austin.trading.repository.ThemeSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * P2.2 Benchmark Analytics.
 *
 * <p>Compares strategy performance (from trade attribution) against:
 * <ul>
 *   <li>Market benchmark — mean {@code avg_gain_pct} of all theme snapshots in the period</li>
 *   <li>Theme benchmark  — mean {@code avg_gain_pct} of themes actually traded</li>
 * </ul>
 * Generates one {@link BenchmarkAnalyticsEntity} per period (idempotent by start/end date).</p>
 */
@Service
public class BenchmarkAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkAnalyticsService.class);

    private final BenchmarkAnalyticsEngine        engine;
    private final BenchmarkAnalyticsRepository    benchmarkRepo;
    private final TradeAttributionService         attributionService;
    private final ThemeSnapshotRepository         themeSnapshotRepo;

    public BenchmarkAnalyticsService(
            BenchmarkAnalyticsEngine engine,
            BenchmarkAnalyticsRepository benchmarkRepo,
            TradeAttributionService attributionService,
            ThemeSnapshotRepository themeSnapshotRepo) {
        this.engine            = engine;
        this.benchmarkRepo     = benchmarkRepo;
        this.attributionService = attributionService;
        this.themeSnapshotRepo  = themeSnapshotRepo;
    }

    /**
     * Generate and persist a benchmark report for the given period.
     * Idempotent — returns existing record if one already exists for start/end.
     */
    @Transactional
    public Optional<BenchmarkReport> generateForPeriod(LocalDate startDate, LocalDate endDate) {
        if (benchmarkRepo.findByStartDateAndEndDate(startDate, endDate).isPresent()) {
            log.debug("[BenchmarkAnalytics] 已存在 {}/{} 的報告，跳過", startDate, endDate);
            return benchmarkRepo.findByStartDateAndEndDate(startDate, endDate)
                    .map(this::toReport);
        }

        List<TradeAttributionOutput> attributions = attributionService.findAll().stream()
                .filter(a -> a.entryDate() != null
                        && !a.entryDate().isBefore(startDate)
                        && !a.entryDate().isAfter(endDate))
                .toList();

        if (attributions.isEmpty()) {
            log.info("[BenchmarkAnalytics] {} ~ {} 無歸因資料，跳過", startDate, endDate);
            return Optional.empty();
        }

        List<ThemeSnapshotEntity> snapshots = themeSnapshotRepo.findByTradingDateBetween(startDate, endDate);

        BigDecimal strategyAvgReturn = computeStrategyAvgReturn(attributions);
        BigDecimal strategyWinRate   = computeStrategyWinRate(attributions);
        BigDecimal marketAvgGain     = computeMarketAvgGain(snapshots);
        BigDecimal tradedThemeGain   = computeTradedThemeAvgGain(attributions, snapshots);

        BenchmarkInput input = new BenchmarkInput(
                startDate, endDate,
                strategyAvgReturn, strategyWinRate, attributions.size(),
                marketAvgGain, tradedThemeGain
        );

        BenchmarkReport report = engine.evaluate(input);
        if (report == null) return Optional.empty();

        benchmarkRepo.save(toEntity(report));
        log.info("[BenchmarkAnalytics] {}/{} marketVerdict={} themeVerdict={} alpha={}",
                startDate, endDate, report.marketVerdict(), report.themeVerdict(), report.marketAlpha());
        return Optional.of(report);
    }

    /** Find the most recent benchmark record. */
    public Optional<BenchmarkReport> findLatest() {
        return benchmarkRepo.findLatest().map(this::toReport);
    }

    /** All benchmark records ordered by end date desc. */
    public List<BenchmarkReport> findAll() {
        return benchmarkRepo.findAllOrderByEndDateDesc().stream().map(this::toReport).toList();
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private BigDecimal computeStrategyAvgReturn(List<TradeAttributionOutput> attrs) {
        List<BigDecimal> returns = attrs.stream()
                .filter(a -> a.pnlPct() != null)
                .map(TradeAttributionOutput::pnlPct)
                .toList();
        if (returns.isEmpty()) return null;
        BigDecimal sum = returns.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(new BigDecimal(returns.size()), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal computeStrategyWinRate(List<TradeAttributionOutput> attrs) {
        long total = attrs.stream().filter(a -> a.pnlPct() != null).count();
        if (total == 0) return null;
        long wins = attrs.stream().filter(a -> a.pnlPct() != null && a.pnlPct().signum() > 0).count();
        return new BigDecimal(wins)
                .divide(new BigDecimal(total), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    private BigDecimal computeMarketAvgGain(List<ThemeSnapshotEntity> snapshots) {
        List<BigDecimal> gains = snapshots.stream()
                .filter(s -> s.getAvgGainPct() != null)
                .map(ThemeSnapshotEntity::getAvgGainPct)
                .toList();
        if (gains.isEmpty()) return null;
        BigDecimal sum = gains.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(new BigDecimal(gains.size()), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal computeTradedThemeAvgGain(List<TradeAttributionOutput> attrs,
                                                  List<ThemeSnapshotEntity> snapshots) {
        // Theme tag not persisted in TradeAttributionOutput v1 — fall back to market average.
        // When themeTag is added to attribution, this can be refined.
        return computeMarketAvgGain(snapshots);
    }

    private BenchmarkAnalyticsEntity toEntity(BenchmarkReport r) {
        BenchmarkAnalyticsEntity e = new BenchmarkAnalyticsEntity();
        e.setStartDate(r.startDate());
        e.setEndDate(r.endDate());
        e.setStrategyAvgReturn(r.strategyAvgReturn());
        e.setMarketAvgGain(r.marketAvgGain());
        e.setTradedThemeAvgGain(r.tradedThemeAvgGain());
        e.setMarketAlpha(r.marketAlpha());
        e.setThemeAlpha(r.themeAlpha());
        e.setMarketVerdict(r.marketVerdict());
        e.setThemeVerdict(r.themeVerdict());
        e.setTradeCount(r.tradeCount());
        e.setPayloadJson(r.payloadJson());
        return e;
    }

    private BenchmarkReport toReport(BenchmarkAnalyticsEntity e) {
        return new BenchmarkReport(
                e.getStartDate(), e.getEndDate(),
                e.getStrategyAvgReturn(), e.getMarketAvgGain(), e.getTradedThemeAvgGain(),
                e.getMarketAlpha(), e.getThemeAlpha(),
                e.getMarketVerdict(), e.getThemeVerdict(),
                e.getTradeCount() != null ? e.getTradeCount() : 0,
                e.getPayloadJson());
    }
}
