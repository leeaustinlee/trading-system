package com.austin.trading.service;

import com.austin.trading.dto.response.PaperTradeStatsResponse;
import com.austin.trading.dto.response.PaperTradeStatsResponse.GroupStats;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.repository.PaperTradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.mockito.Mockito.mock;

class PaperTradeStatsServiceTests {

    private PaperTradeStatsService service;

    @BeforeEach
    void setUp() {
        service = new PaperTradeStatsService(mock(PaperTradeRepository.class));
    }

    private PaperTradeEntity trade(double pnlPct, int hold, String grade, String theme,
                                   String exitReason, String regime) {
        PaperTradeEntity t = new PaperTradeEntity();
        t.setSymbol("X");
        t.setEntryDate(LocalDate.of(2026, 4, 1));
        t.setExitDate(LocalDate.of(2026, 4, 1).plusDays(hold));
        t.setHoldingDays(hold);
        t.setPnlPct(BigDecimal.valueOf(pnlPct));
        t.setPnlAmount(BigDecimal.valueOf(pnlPct * 100));
        t.setEntryGrade(grade);
        t.setThemeTag(theme);
        t.setExitReason(exitReason);
        t.setEntryRegime(regime);
        t.setStatus("CLOSED");
        return t;
    }

    @Test
    void emptyList_returnsAllZeros_noNaN() {
        PaperTradeStatsResponse r = service.computeStatsFromList(List.of());
        assertThat(r.totalTrades()).isZero();
        assertThat(r.winRate().doubleValue()).isEqualTo(0.0);
        assertThat(r.profitFactor().doubleValue()).isEqualTo(0.0);
        assertThat(r.maxDrawdownPct().doubleValue()).isEqualTo(0.0);
        assertThat(r.sharpeRatio().doubleValue()).isEqualTo(0.0);
        assertThat(r.byGrade()).isEmpty();
    }

    @Test
    void winRate_3wins_2losses_returns60pct() {
        List<PaperTradeEntity> trades = List.of(
                trade(2.0, 3, "B_TRIAL", "AI", "TP1_HIT", "BULL"),
                trade(3.5, 4, "B_TRIAL", "AI", "TP1_HIT", "BULL"),
                trade(1.2, 2, "B_TRIAL", "AI", "TP1_HIT", "BULL"),
                trade(-1.5, 5, "B_TRIAL", "AI", "STOP_LOSS", "BULL"),
                trade(-3.0, 6, "B_TRIAL", "AI", "STOP_LOSS", "BULL")
        );
        PaperTradeStatsResponse r = service.computeStatsFromList(trades);
        assertThat(r.totalTrades()).isEqualTo(5);
        assertThat(r.winTrades()).isEqualTo(3);
        assertThat(r.lossTrades()).isEqualTo(2);
        assertThat(r.winRate().doubleValue()).isCloseTo(0.6, offset(0.0001));
    }

    @Test
    void profitFactor_sumWinsOverAbsSumLosses() {
        List<PaperTradeEntity> trades = List.of(
                trade(4.0, 3, "B_TRIAL", "AI", "TP1_HIT", "BULL"),
                trade(2.0, 4, "B_TRIAL", "AI", "TP1_HIT", "BULL"),
                trade(-1.5, 5, "B_TRIAL", "AI", "STOP_LOSS", "BULL")
        );
        PaperTradeStatsResponse r = service.computeStatsFromList(trades);
        // sum wins = 6.0, sum losses = -1.5 => pf = 4.0
        assertThat(r.profitFactor().doubleValue()).isCloseTo(4.0, offset(0.001));
    }

    @Test
    void sharpeRatio_zeroVariance_returnsZero() {
        List<PaperTradeEntity> trades = List.of(
                trade(2.0, 3, "B_TRIAL", "AI", "TP1_HIT", "BULL"),
                trade(2.0, 3, "B_TRIAL", "AI", "TP1_HIT", "BULL"),
                trade(2.0, 3, "B_TRIAL", "AI", "TP1_HIT", "BULL")
        );
        PaperTradeStatsResponse r = service.computeStatsFromList(trades);
        assertThat(r.sharpeRatio().doubleValue()).isEqualTo(0.0);
    }

    @Test
    void sharpeRatio_consistentPositive_isPositive() {
        List<PaperTradeEntity> trades = List.of(
                trade(3.0, 3, "B_TRIAL", "AI", "TP1_HIT", "BULL"),
                trade(2.5, 4, "B_TRIAL", "AI", "TP1_HIT", "BULL"),
                trade(3.5, 3, "B_TRIAL", "AI", "TP1_HIT", "BULL"),
                trade(2.8, 4, "B_TRIAL", "AI", "TP1_HIT", "BULL")
        );
        PaperTradeStatsResponse r = service.computeStatsFromList(trades);
        assertThat(r.sharpeRatio().doubleValue()).isGreaterThan(0.5);
    }

    @Test
    void maxDrawdown_strictlyIncreasing_isZero() {
        List<PaperTradeEntity> trades = List.of(
                trade(1.0, 3, "B_TRIAL", "AI", "TP1_HIT", "BULL"),
                trade(2.0, 3, "B_TRIAL", "AI", "TP1_HIT", "BULL"),
                trade(1.5, 3, "B_TRIAL", "AI", "TP1_HIT", "BULL")
        );
        PaperTradeStatsResponse r = service.computeStatsFromList(trades);
        assertThat(r.maxDrawdownPct().doubleValue()).isCloseTo(0.0, offset(0.01));
    }

    @Test
    void maxDrawdown_peakThenTrough_correctlyComputed() {
        // +5 (equity 105), -10 (equity 94.5) => peak 105 → trough 94.5 → dd = 10%
        List<PaperTradeEntity> trades = List.of(
                trade(5.0, 3, "B_TRIAL", "AI", "TP1_HIT", "BULL"),
                trade(-10.0, 3, "B_TRIAL", "AI", "STOP_LOSS", "BULL")
        );
        PaperTradeStatsResponse r = service.computeStatsFromList(trades);
        assertThat(r.maxDrawdownPct().doubleValue()).isCloseTo(10.0, offset(0.01));
    }

    @Test
    void medianHoldDays_evenCount_isAverageOfTwoMiddle() {
        // hold days [3, 4, 5, 6] → median = (4+5)/2 = 4.5
        List<PaperTradeEntity> trades = List.of(
                trade(2.0, 3, "B", "A", "TP", "B"),
                trade(2.0, 4, "B", "A", "TP", "B"),
                trade(2.0, 5, "B", "A", "TP", "B"),
                trade(2.0, 6, "B", "A", "TP", "B")
        );
        PaperTradeStatsResponse r = service.computeStatsFromList(trades);
        assertThat(r.medianHoldDays().doubleValue()).isCloseTo(4.5, offset(0.01));
    }

    @Test
    void byGrade_groupsCorrectly() {
        List<PaperTradeEntity> trades = List.of(
                trade(3.0, 3, "A_PLUS",  "AI",  "TP1_HIT",  "BULL"),
                trade(2.0, 4, "A_NORMAL","AI",  "TP1_HIT",  "BULL"),
                trade(-1.5, 5, "B_TRIAL","AI",  "STOP_LOSS","BULL"),
                trade(1.0, 4, "B_TRIAL", "AI",  "TP1_HIT",  "BULL")
        );
        PaperTradeStatsResponse r = service.computeStatsFromList(trades);
        Map<String, GroupStats> byGrade = r.byGrade();
        assertThat(byGrade).containsKeys("A_PLUS", "A_NORMAL", "B_TRIAL");
        assertThat(byGrade.get("A_PLUS").n()).isEqualTo(1);
        assertThat(byGrade.get("B_TRIAL").n()).isEqualTo(2);
    }

    @Test
    void byExitReason_groupsCorrectly_andCountsAreRight() {
        List<PaperTradeEntity> trades = List.of(
                trade(3.0, 3, "B", "AI", "TP1_HIT", "B"),
                trade(2.0, 4, "B", "AI", "TP1_HIT", "B"),
                trade(-1.5, 5, "B", "AI", "STOP_LOSS", "B"),
                trade(1.0, 4, "B", "AI", "TIME_EXIT", "B")
        );
        PaperTradeStatsResponse r = service.computeStatsFromList(trades);
        Map<String, GroupStats> byReason = r.byExitReason();
        assertThat(byReason).containsKeys("TP1_HIT", "STOP_LOSS", "TIME_EXIT");
        assertThat(byReason.get("TP1_HIT").n()).isEqualTo(2);
        assertThat(byReason.get("TP1_HIT").totalPnlPct().doubleValue()).isCloseTo(5.0, offset(0.01));
    }
}
