package com.austin.trading.service;

import com.austin.trading.dto.internal.RankingTopNShadowResultDto;
import com.austin.trading.dto.response.RankingTopNShadowCalibrationResponse;
import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.RankingTopNShadowResultEntity;
import com.austin.trading.entity.StockRankingSnapshotEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.RankingTopNShadowResultRepository;
import com.austin.trading.repository.StockRankingSnapshotRepository;
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
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Shadow-only Top-N ranking observer. */
@Service
public class RankingTopNShadowService {

    private static final Logger log = LoggerFactory.getLogger(RankingTopNShadowService.class);
    private static final String DEFAULT_RUN_ID = "P0_SHADOW_TOPN";
    private static final BigDecimal FIVE = new BigDecimal("5");
    private static final BigDecimal TEN = new BigDecimal("10");
    private static final int MAX_REPORT_DAYS = 365;

    private final StockRankingSnapshotRepository snapshotRepository;
    private final RankingTopNShadowResultRepository resultRepository;
    private final CandidateForwardTrackingRepository forwardTrackingRepository;

    public RankingTopNShadowService(StockRankingSnapshotRepository snapshotRepository,
                                    RankingTopNShadowResultRepository resultRepository,
                                    CandidateForwardTrackingRepository forwardTrackingRepository) {
        this.snapshotRepository = snapshotRepository;
        this.resultRepository = resultRepository;
        this.forwardTrackingRepository = forwardTrackingRepository;
    }

    public void safeRebuildForDate(LocalDate tradingDate) {
        try {
            rebuildForDate(tradingDate);
        } catch (Exception ex) {
            log.warn("Ranking Top-N shadow rebuild failed for {}: {}", tradingDate, ex.toString());
        }
    }

    @Transactional(readOnly = true)
    public RankingTopNShadowCalibrationResponse calibration(int days) {
        Window window = window(days);
        List<RankingTopNShadowResultEntity> rows = loadRows(window);
        RankingTopNShadowCalibrationResponse.TopNWindowComparison top3 = topNWindow(rows, 3);
        RankingTopNShadowCalibrationResponse.TopNWindowComparison top5 = topNWindow(rows, 5);
        RankingTopNShadowCalibrationResponse.TopNWindowComparison top10 = topNWindow(rows, 10);
        RankingTopNShadowCalibrationResponse.TopNWindowComparison top20 = topNWindow(rows, 20);

        return new RankingTopNShadowCalibrationResponse(
                true, true, true, true,
                window.requestedDays(), window.start(), window.end(), rows.size(), distinctDays(rows),
                top3, top5, top10, top20,
                List.of(delta(rows, 3, 5), delta(rows, 3, 10), delta(rows, 3, 20)),
                dataGaps(rows));
    }

    @Transactional(readOnly = true)
    public RankingTopNShadowCalibrationResponse.MissedWinnersResponse missedWinners(int days) {
        Window window = window(days);
        List<RankingTopNShadowResultEntity> rows = loadRows(window);
        List<RankingTopNShadowResultEntity> missed = rows.stream()
                .filter(RankingTopNShadowService::isMissedWinner)
                .sorted(Comparator.comparing(RankingTopNShadowResultEntity::getActualReturn10d,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(RankingTopNShadowResultEntity::getActualReturn5d,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(RankingTopNShadowResultEntity::getTradingDate,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(RankingTopNShadowResultEntity::getRankingRank,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        return new RankingTopNShadowCalibrationResponse.MissedWinnersResponse(
                true, true, true, true,
                window.requestedDays(), window.start(), window.end(), missed.size(),
                average(missed, RankingTopNShadowResultEntity::getActualReturn5d),
                average(missed, RankingTopNShadowResultEntity::getActualReturn10d),
                missed.stream().map(this::toDto).toList(), dataGaps(rows));
    }

    @Transactional(readOnly = true)
    public RankingTopNShadowCalibrationResponse.ThemeQuotaResponse themeQuota(int days) {
        Window window = window(days);
        List<RankingTopNShadowResultEntity> rows = loadRows(window);
        Map<String, List<RankingTopNShadowResultEntity>> byTheme = rows.stream()
                .collect(Collectors.groupingBy(e -> normalizeTheme(e.getThemeTag()), LinkedHashMap::new, Collectors.toList()));
        List<RankingTopNShadowCalibrationResponse.ThemeQuotaAnalysis> themes = byTheme.entrySet().stream()
                .map(e -> themeQuota(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(RankingTopNShadowCalibrationResponse.ThemeQuotaAnalysis::missedWinnerCount).reversed()
                        .thenComparing(RankingTopNShadowCalibrationResponse.ThemeQuotaAnalysis::totalRows, Comparator.reverseOrder())
                        .thenComparing(RankingTopNShadowCalibrationResponse.ThemeQuotaAnalysis::themeTag))
                .toList();
        return new RankingTopNShadowCalibrationResponse.ThemeQuotaResponse(
                true, true, true, true,
                window.requestedDays(), window.start(), window.end(), themes.size(), themes, dataGaps(rows));
    }

    @Transactional
    public int rebuildForDate(LocalDate tradingDate) {
        if (tradingDate == null) {
            return 0;
        }
        List<StockRankingSnapshotEntity> snapshots = snapshotRepository.findByTradingDate(tradingDate).stream()
                .sorted(Comparator.comparing(StockRankingSnapshotEntity::getSelectionScore,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(StockRankingSnapshotEntity::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        int saved = 0;
        for (int i = 0; i < snapshots.size(); i++) {
            StockRankingSnapshotEntity snapshot = snapshots.get(i);
            if (isBlank(snapshot.getSymbol())) {
                continue;
            }
            int rank = i + 1;
            ForwardReturns returns = findForwardReturns(tradingDate, snapshot.getSymbol());
            String bucket = bucketForRank(rank);
            boolean missed = missedByTop3(rank, returns.return5(), returns.return10());
            RankingTopNShadowResultEntity entity = resultRepository
                    .findByTradingDateAndRunIdAndSymbol(tradingDate, DEFAULT_RUN_ID, snapshot.getSymbol())
                    .orElseGet(RankingTopNShadowResultEntity::new);
            entity.setTradingDate(tradingDate);
            entity.setRunId(DEFAULT_RUN_ID);
            entity.setSnapshotId(snapshot.getId());
            entity.setSymbol(snapshot.getSymbol());
            entity.setThemeTag(snapshot.getThemeTag());
            entity.setBucket(bucket);
            entity.setCurrentSelected(rank <= 3);
            entity.setWouldSelectTop5(rank <= 5);
            entity.setWouldSelectTop10(rank <= 10);
            entity.setWouldSelectTop20(rank <= 20);
            entity.setRankingRank(rank);
            entity.setRankingScore(snapshot.getSelectionScore());
            entity.setRankingStatus(bucket);
            entity.setRankingReason(buildReason(rank, returns));
            entity.setActualReturn1d(returns.return1());
            entity.setActualReturn5d(returns.return5());
            entity.setActualReturn10d(returns.return10());
            entity.setMaxDrawdown10d(returns.maxDrawdown10d());
            entity.setMissedByTop3(missed);
            entity.setScoreBreakdownJson(snapshot.getScoreBreakdownJson());
            entity.setTraceSource("SHADOW");
            entity.setTraceStatus("ACTIVE");
            resultRepository.save(entity);
            saved++;
        }
        return saved;
    }

    static String bucketForRank(int rank) {
        if (rank <= 0) return "OUTSIDE_TOP20";
        if (rank == 1) return "TOP1";
        if (rank <= 3) return "TOP2_3";
        if (rank <= 5) return "TOP4_5";
        if (rank <= 10) return "TOP6_10";
        if (rank <= 20) return "TOP11_20";
        return "OUTSIDE_TOP20";
    }

    static boolean missedByTop3(int rank, BigDecimal return5, BigDecimal return10) {
        if (rank <= 3) return false;
        return greaterThan(return5, FIVE) || greaterThan(return10, TEN);
    }

    private List<RankingTopNShadowResultEntity> loadRows(Window window) {
        return resultRepository.findByTradingDateBetweenOrderByTradingDateDescRankingRankAsc(window.start(), window.end());
    }

    private Window window(int days) {
        int requestedDays = Math.max(1, Math.min(days, MAX_REPORT_DAYS));
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(requestedDays - 1L);
        return new Window(requestedDays, start, end);
    }

    private List<String> dataGaps(List<RankingTopNShadowResultEntity> rows) {
        List<String> gaps = new ArrayList<>();
        if (rows.isEmpty()) {
            gaps.add("NO_ROWS_IN_REQUESTED_WINDOW:ranking_topn_shadow_result");
        }
        boolean noForwardReturns = rows.stream().noneMatch(e -> e.getActualReturn1d() != null
                || e.getActualReturn5d() != null || e.getActualReturn10d() != null);
        if (!rows.isEmpty() && noForwardReturns) {
            gaps.add("NO_FORWARD_RETURNS_IN_REQUESTED_WINDOW:ranking_topn_shadow_result");
        }
        return List.copyOf(gaps);
    }

    private long distinctDays(List<RankingTopNShadowResultEntity> rows) {
        return rows.stream().map(RankingTopNShadowResultEntity::getTradingDate).filter(d -> d != null).distinct().count();
    }

    private RankingTopNShadowCalibrationResponse.TopNWindowComparison topNWindow(
            List<RankingTopNShadowResultEntity> rows, int topN) {
        List<RankingTopNShadowResultEntity> selected = rows.stream().filter(rankAtMost(topN)).toList();
        long missed = selected.stream().filter(RankingTopNShadowService::isMissedWinner).count();
        return new RankingTopNShadowCalibrationResponse.TopNWindowComparison(
                topN, selected.size(), distinctSymbols(selected),
                averageInteger(selected, RankingTopNShadowResultEntity::getRankingRank),
                average(selected, RankingTopNShadowResultEntity::getRankingScore),
                average(selected, RankingTopNShadowResultEntity::getActualReturn1d),
                winRate(selected, RankingTopNShadowResultEntity::getActualReturn1d),
                average(selected, RankingTopNShadowResultEntity::getActualReturn5d),
                winRate(selected, RankingTopNShadowResultEntity::getActualReturn5d),
                average(selected, RankingTopNShadowResultEntity::getActualReturn10d),
                winRate(selected, RankingTopNShadowResultEntity::getActualReturn10d),
                average(selected, RankingTopNShadowResultEntity::getMaxDrawdown10d),
                missed, rate(missed, selected.size()));
    }

    private RankingTopNShadowCalibrationResponse.TopNDeltaComparison delta(
            List<RankingTopNShadowResultEntity> rows, int baselineTopN, int candidateTopN) {
        List<RankingTopNShadowResultEntity> incremental = rows.stream()
                .filter(e -> rankGreaterThan(e, baselineTopN) && rankAtMost(candidateTopN).test(e))
                .toList();
        return new RankingTopNShadowCalibrationResponse.TopNDeltaComparison(
                "Top" + baselineTopN + " vs Top" + candidateTopN,
                baselineTopN, candidateTopN, incremental.size(),
                incremental.stream().filter(RankingTopNShadowService::isMissedWinner).count(),
                average(incremental, RankingTopNShadowResultEntity::getActualReturn5d),
                average(incremental, RankingTopNShadowResultEntity::getActualReturn10d),
                winRate(incremental, RankingTopNShadowResultEntity::getActualReturn5d),
                winRate(incremental, RankingTopNShadowResultEntity::getActualReturn10d));
    }

    private RankingTopNShadowCalibrationResponse.ThemeQuotaAnalysis themeQuota(
            String themeTag, List<RankingTopNShadowResultEntity> rows) {
        List<RankingTopNShadowResultEntity> outsideTop3 = rows.stream().filter(e -> rankGreaterThan(e, 3)).toList();
        long missed = outsideTop3.stream().filter(RankingTopNShadowService::isMissedWinner).count();
        int suggestedQuota = suggestedQuota(rows, missed);
        return new RankingTopNShadowCalibrationResponse.ThemeQuotaAnalysis(
                themeTag, rows.size(), count(rows, rankAtMost(3)), count(rows, rankAtMost(5)),
                count(rows, rankAtMost(10)), count(rows, rankAtMost(20)), outsideTop3.size(), missed,
                rate(missed, outsideTop3.size()),
                average(rows, RankingTopNShadowResultEntity::getActualReturn5d),
                average(rows, RankingTopNShadowResultEntity::getActualReturn10d),
                average(rows.stream().filter(rankAtMost(3)).toList(), RankingTopNShadowResultEntity::getActualReturn5d),
                average(outsideTop3, RankingTopNShadowResultEntity::getActualReturn5d),
                suggestedQuota, quotaRationale(missed, suggestedQuota));
    }

    private int suggestedQuota(List<RankingTopNShadowResultEntity> rows, long missedOutsideTop3) {
        long top3 = count(rows, rankAtMost(3));
        if (missedOutsideTop3 >= 2) {
            return (int) Math.min(5, Math.max(3, top3 + 1));
        }
        return (int) Math.min(3, top3);
    }

    private String quotaRationale(long missedOutsideTop3, int suggestedQuota) {
        if (missedOutsideTop3 >= 2) {
            return "SHADOW_ONLY_REVIEW:multiple missed winners outside current Top3; suggestedTop3Quota=" + suggestedQuota;
        }
        if (missedOutsideTop3 == 1) {
            return "SHADOW_ONLY_MONITOR:one missed winner outside current Top3";
        }
        return "SHADOW_ONLY_NO_CHANGE:no missed winners outside current Top3";
    }

    private long count(List<RankingTopNShadowResultEntity> rows, Predicate<RankingTopNShadowResultEntity> predicate) {
        return rows.stream().filter(predicate).count();
    }

    private Predicate<RankingTopNShadowResultEntity> rankAtMost(int topN) {
        return e -> e.getRankingRank() != null && e.getRankingRank() >= 1 && e.getRankingRank() <= topN;
    }

    private boolean rankGreaterThan(RankingTopNShadowResultEntity e, int rank) {
        return e.getRankingRank() != null && e.getRankingRank() > rank;
    }

    private static boolean isMissedWinner(RankingTopNShadowResultEntity e) {
        return Boolean.TRUE.equals(e.getMissedByTop3()) || missedByTop3(
                e.getRankingRank() == null ? Integer.MAX_VALUE : e.getRankingRank(),
                e.getActualReturn5d(), e.getActualReturn10d());
    }

    private long distinctSymbols(List<RankingTopNShadowResultEntity> rows) {
        Set<String> symbols = rows.stream().map(RankingTopNShadowResultEntity::getSymbol)
                .filter(s -> s != null && !s.isBlank()).collect(Collectors.toSet());
        return symbols.size();
    }

    private BigDecimal average(List<RankingTopNShadowResultEntity> rows,
                               Function<RankingTopNShadowResultEntity, BigDecimal> extractor) {
        List<BigDecimal> values = rows.stream().map(extractor).filter(v -> v != null).toList();
        if (values.isEmpty()) {
            return null;
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal averageInteger(List<RankingTopNShadowResultEntity> rows,
                                      Function<RankingTopNShadowResultEntity, Integer> extractor) {
        List<Integer> values = rows.stream().map(extractor).filter(v -> v != null).toList();
        if (values.isEmpty()) {
            return null;
        }
        BigDecimal sum = values.stream().map(BigDecimal::valueOf).reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal winRate(List<RankingTopNShadowResultEntity> rows,
                               Function<RankingTopNShadowResultEntity, BigDecimal> extractor) {
        List<BigDecimal> values = rows.stream().map(extractor).filter(v -> v != null).toList();
        if (values.isEmpty()) {
            return null;
        }
        long wins = values.stream().filter(v -> v.compareTo(BigDecimal.ZERO) > 0).count();
        return rate(wins, values.size());
    }

    private BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(4);
        }
        return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private String normalizeTheme(String themeTag) {
        return themeTag == null || themeTag.isBlank() ? "UNKNOWN" : themeTag;
    }

    private RankingTopNShadowResultDto toDto(RankingTopNShadowResultEntity e) {
        return new RankingTopNShadowResultDto(e.getId(), e.getTradingDate(), e.getRunId(), e.getSnapshotId(), e.getSymbol(),
                e.getStockName(), e.getThemeTag(), e.getBucket(), e.getCurrentSelected(), e.getWouldSelectTop5(),
                e.getWouldSelectTop10(), e.getWouldSelectTop20(), e.getRankingRank(), e.getRankingScore(), e.getRankingStatus(),
                e.getRankingReason(), e.getCandidateId(), e.getSourceTraceId(), e.getActualReturn1d(), e.getActualReturn5d(),
                e.getActualReturn10d(), e.getMaxDrawdown10d(), e.getMissedByTop3(), e.getScoreBreakdownJson(),
                e.getTraceSource(), e.getTraceStatus(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private String buildReason(int rank, ForwardReturns returns) {
        boolean missed = missedByTop3(rank, returns.return5(), returns.return10());
        return "bucket=" + bucketForRank(rank)
                + ", currentSelected=" + (rank <= 3)
                + ", wouldSelectTop5=" + (rank <= 5)
                + ", wouldSelectTop10=" + (rank <= 10)
                + ", wouldSelectTop20=" + (rank <= 20)
                + ", missedByTop3=" + missed
                + (returns.available() ? ", return5=" + returns.return5() + ", return10=" + returns.return10() : ", returns=null");
    }

    private ForwardReturns findForwardReturns(LocalDate tradingDate, String symbol) {
        try {
            List<CandidateForwardTrackingEntity> rows = forwardTrackingRepository.findByTradingDateAndStockId(tradingDate, symbol);
            Optional<CandidateForwardTrackingEntity> row = rows.stream().findFirst();
            return row.map(v -> new ForwardReturns(v.getT1CloseReturnPct(), v.getT5CloseReturnPct(),
                            v.getT10CloseReturnPct(), v.getMaxDrawdownPct(), true))
                    .orElseGet(() -> new ForwardReturns(null, null, null, null, false));
        } catch (Exception ex) {
            log.warn("Forward returns lookup failed for {} {}: {}", tradingDate, symbol, ex.toString());
            return new ForwardReturns(null, null, null, null, false);
        }
    }

    private static boolean greaterThan(BigDecimal value, BigDecimal threshold) {
        return value != null && value.compareTo(threshold) > 0;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record ForwardReturns(BigDecimal return1, BigDecimal return5, BigDecimal return10,
                                  BigDecimal maxDrawdown10d, boolean available) { }

    private record Window(int requestedDays, LocalDate start, LocalDate end) { }
}
