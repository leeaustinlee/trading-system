package com.austin.trading.service;

import com.austin.trading.entity.RankingTopNShadowResultEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.RankingTopNShadowResultRepository;
import com.austin.trading.repository.StockRankingSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RankingTopNShadowCalibrationServiceTest {

    @Test
    void calibrationComparesTop3AgainstBroaderShadowWindowsReadOnly() {
        RankingTopNShadowResultRepository resultRepository = Mockito.mock(RankingTopNShadowResultRepository.class);
        StockRankingSnapshotRepository snapshotRepository = Mockito.mock(StockRankingSnapshotRepository.class);
        CandidateForwardTrackingRepository forwardRepository = Mockito.mock(CandidateForwardTrackingRepository.class);
        when(resultRepository.findByTradingDateBetweenOrderByTradingDateDescRankingRankAsc(any(), any()))
                .thenReturn(List.of(
                        row("2330", "AI", 1, "90", "1", "3", "4", false),
                        row("2317", "AI", 2, "80", "-1", "-2", "-3", false),
                        row("2454", "AUTO", 3, "70", "2", "4", "5", false),
                        row("9999", "AI", 4, "60", "3", "8", "12", true),
                        row("8888", "AUTO", 8, "50", "-2", "6", "9", true)
                ));
        RankingTopNShadowService service = new RankingTopNShadowService(snapshotRepository, resultRepository, forwardRepository);

        var response = service.calibration(60);

        assertThat(response.readOnly()).isTrue();
        assertThat(response.shadowOnly()).isTrue();
        assertThat(response.doesNotAffectRanking()).isTrue();
        assertThat(response.doesNotAffectBuySell()).isTrue();
        assertThat(response.totalRows()).isEqualTo(5);
        assertThat(response.top3().selectedCount()).isEqualTo(3);
        assertThat(response.top5().selectedCount()).isEqualTo(4);
        assertThat(response.top10().selectedCount()).isEqualTo(5);
        assertThat(response.top5().missedWinnerCount()).isEqualTo(1);
        assertThat(response.comparisons()).extracting("comparison")
                .containsExactly("Top3 vs Top5", "Top3 vs Top10", "Top3 vs Top20");
        assertThat(response.comparisons().get(0).incrementalRows()).isEqualTo(1);
        assertThat(response.comparisons().get(0).incrementalMissedWinners()).isEqualTo(1);
        verify(resultRepository, never()).save(any());
        verify(snapshotRepository, never()).findByTradingDate(any());
        verify(forwardRepository, never()).findByTradingDateAndStockId(any(), any());
    }

    @Test
    void missedWinnersReturnsOnlyOutsideTop3WinnersAndNeverWrites() {
        RankingTopNShadowResultRepository resultRepository = Mockito.mock(RankingTopNShadowResultRepository.class);
        when(resultRepository.findByTradingDateBetweenOrderByTradingDateDescRankingRankAsc(any(), any()))
                .thenReturn(List.of(
                        row("2330", "AI", 1, "90", "1", "20", "30", false),
                        row("9999", "AI", 4, "60", "3", "8", "12", true),
                        row("7777", "AUTO", 7, "55", "0", "1", "2", false)
                ));
        RankingTopNShadowService service = new RankingTopNShadowService(null, resultRepository, null);

        var response = service.missedWinners(60);

        assertThat(response.readOnly()).isTrue();
        assertThat(response.shadowOnly()).isTrue();
        assertThat(response.totalMissedWinners()).isEqualTo(1);
        assertThat(response.missedWinners()).extracting("symbol").containsExactly("9999");
        assertThat(response.averageReturn5d()).isEqualByComparingTo(new BigDecimal("8.0000"));
        verify(resultRepository, never()).save(any());
    }

    @Test
    void themeQuotaGroupsThemesAndMarksShadowOnlySuggestedQuota() {
        RankingTopNShadowResultRepository resultRepository = Mockito.mock(RankingTopNShadowResultRepository.class);
        when(resultRepository.findByTradingDateBetweenOrderByTradingDateDescRankingRankAsc(any(), any()))
                .thenReturn(List.of(
                        row("1001", "AI", 1, "90", "1", "2", "3", false),
                        row("1002", "AI", 4, "80", "2", "7", "9", true),
                        row("1003", "AI", 5, "70", "3", "8", "11", true),
                        row("2001", "AUTO", 2, "75", "1", "1", "1", false)
                ));
        RankingTopNShadowService service = new RankingTopNShadowService(null, resultRepository, null);

        var response = service.themeQuota(60);

        assertThat(response.readOnly()).isTrue();
        assertThat(response.shadowOnly()).isTrue();
        assertThat(response.doesNotAffectRanking()).isTrue();
        assertThat(response.totalThemes()).isEqualTo(2);
        assertThat(response.themes().get(0).themeTag()).isEqualTo("AI");
        assertThat(response.themes().get(0).missedWinnerCount()).isEqualTo(2);
        assertThat(response.themes().get(0).shadowSuggestedTop3Quota()).isEqualTo(3);
        assertThat(response.themes().get(0).shadowQuotaRationale()).startsWith("SHADOW_ONLY_REVIEW");
        verify(resultRepository, never()).save(any());
    }

    @Test
    void emptyDataReturnsExplicitGapAndZeroWindows() {
        RankingTopNShadowResultRepository resultRepository = Mockito.mock(RankingTopNShadowResultRepository.class);
        when(resultRepository.findByTradingDateBetweenOrderByTradingDateDescRankingRankAsc(any(), any()))
                .thenReturn(List.of());
        RankingTopNShadowService service = new RankingTopNShadowService(null, resultRepository, null);

        var response = service.calibration(60);

        assertThat(response.totalRows()).isZero();
        assertThat(response.top3().selectedCount()).isZero();
        assertThat(response.top5().missedWinnerRate()).isEqualByComparingTo(new BigDecimal("0.0000"));
        assertThat(response.dataGaps()).containsExactly("NO_ROWS_IN_REQUESTED_WINDOW:ranking_topn_shadow_result");
        verify(resultRepository, never()).save(any());
    }

    @Test
    void rebuildStillUsesWriteRepositoryOnlyForExplicitRebuildPath() {
        RankingTopNShadowResultRepository resultRepository = Mockito.mock(RankingTopNShadowResultRepository.class);
        StockRankingSnapshotRepository snapshotRepository = Mockito.mock(StockRankingSnapshotRepository.class);
        when(snapshotRepository.findByTradingDate(any())).thenReturn(List.of());
        when(resultRepository.findByTradingDateAndRunIdAndSymbol(any(), any(), any())).thenReturn(Optional.empty());
        RankingTopNShadowService service = new RankingTopNShadowService(snapshotRepository, resultRepository, null);

        int saved = service.rebuildForDate(LocalDate.of(2026, 6, 19));

        assertThat(saved).isZero();
        verify(snapshotRepository).findByTradingDate(LocalDate.of(2026, 6, 19));
        verify(resultRepository, never()).save(any());
    }

    private RankingTopNShadowResultEntity row(String symbol, String theme, int rank, String score,
                                              String return1d, String return5d, String return10d, boolean missed) {
        RankingTopNShadowResultEntity row = new RankingTopNShadowResultEntity();
        row.setTradingDate(LocalDate.of(2026, 6, 19));
        row.setRunId("P0_SHADOW_TOPN");
        row.setSymbol(symbol);
        row.setThemeTag(theme);
        row.setRankingRank(rank);
        row.setRankingScore(new BigDecimal(score));
        row.setActualReturn1d(new BigDecimal(return1d));
        row.setActualReturn5d(new BigDecimal(return5d));
        row.setActualReturn10d(new BigDecimal(return10d));
        row.setMaxDrawdown10d(new BigDecimal("-3"));
        row.setMissedByTop3(missed);
        row.setCurrentSelected(rank <= 3);
        row.setWouldSelectTop5(rank <= 5);
        row.setWouldSelectTop10(rank <= 10);
        row.setWouldSelectTop20(rank <= 20);
        return row;
    }
}
