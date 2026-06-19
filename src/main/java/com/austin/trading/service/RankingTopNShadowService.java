package com.austin.trading.service;

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
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Shadow-only Top-N ranking observer. */
@Service
public class RankingTopNShadowService {

    private static final Logger log = LoggerFactory.getLogger(RankingTopNShadowService.class);
    private static final String DEFAULT_RUN_ID = "P0_SHADOW_TOPN";
    private static final BigDecimal FIVE = new BigDecimal("5");
    private static final BigDecimal TEN = new BigDecimal("10");

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
}
