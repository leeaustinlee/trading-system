package com.austin.trading.service;

import com.austin.trading.dto.response.DrawdownResponse;
import com.austin.trading.entity.DailyPnlEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v2.14：drawdown 計算 unit tests，純資料驗算，不需要 Spring context。
 */
class PnlServiceDrawdownTests {

    @Test
    void emptyRows_returnsZero() {
        DrawdownResponse r = PnlService.computeDrawdownFromRows(List.of(), 90, new BigDecimal("100000"));
        assertThat(r.windowDays()).isEqualTo(90);
        assertThat(r.maxDrawdownPct()).isEqualByComparingTo("0");
        assertThat(r.currentDrawdownPct()).isEqualByComparingTo("0");
        assertThat(r.peakAt()).isNull();
        assertThat(r.troughAt()).isNull();
        assertThat(r.sampleDays()).isEqualTo(0);
    }

    @Test
    void monotonicGain_zeroDrawdown() {
        // 連續 5 天小幅獲利：equity 0→100→300→600→1000→1500
        List<DailyPnlEntity> rows = rows(
                row("2026-04-21", 100),
                row("2026-04-22", 200),
                row("2026-04-23", 300),
                row("2026-04-24", 400),
                row("2026-04-25", 500)
        );
        DrawdownResponse r = PnlService.computeDrawdownFromRows(rows, 90, new BigDecimal("100000"));
        assertThat(r.maxDrawdownPct()).isEqualByComparingTo("0");
        assertThat(r.currentDrawdownPct()).isEqualByComparingTo("0");
        assertThat(r.sampleDays()).isEqualTo(5);
    }

    @Test
    void peakThenTrough_computesMaxDrawdownAndDates() {
        // equity: 1000, 3000, 5000, 4000, 2000, 3500, 1500, 4000
        // peak=5000@04-23, trough=1500@04-27, dd=3500
        List<DailyPnlEntity> rows = rows(
                row("2026-04-21", 1000),
                row("2026-04-22", 2000),
                row("2026-04-23", 2000),
                row("2026-04-24", -1000),
                row("2026-04-25", -2000),
                row("2026-04-26", 1500),
                row("2026-04-27", -2000),
                row("2026-04-28", 2500)
        );
        DrawdownResponse r = PnlService.computeDrawdownFromRows(rows, 90, new BigDecimal("100000"));
        // baseline=100000 → -3500/100000 = -3.5%
        assertThat(r.maxDrawdownPct()).isEqualByComparingTo("-3.5");
        assertThat(r.peakAt()).isEqualTo(LocalDate.of(2026, 4, 23));
        assertThat(r.troughAt()).isEqualTo(LocalDate.of(2026, 4, 27));
        assertThat(r.sampleDays()).isEqualTo(8);
        assertThat(r.baseline()).isEqualByComparingTo("100000");
    }

    @Test
    void currentDrawdown_reflectsLatestEquityVsLatestPeak() {
        // equity 走 0→100→1000→500（最後一日）；目前距 peak 1000 跌 -500
        List<DailyPnlEntity> rows = rows(
                row("2026-04-21", 100),
                row("2026-04-22", 900),     // peak 1000
                row("2026-04-23", -500)     // 現 500，距 peak 1000 → DD 500
        );
        DrawdownResponse r = PnlService.computeDrawdownFromRows(rows, 30, new BigDecimal("10000"));
        // baseline=10000 → 500/10000 = -5.0%
        assertThat(r.currentDrawdownPct()).isEqualByComparingTo("-5");
        assertThat(r.maxDrawdownPct()).isEqualByComparingTo("-5");
    }

    @Test
    void nullBaseline_fallsBackToPeakAbs() {
        // 不傳 baseline，分母用 peak.abs() 或 1
        // equity 0→100→500→200，peak=500, trough dd=300 → 300/500 = -60%
        List<DailyPnlEntity> rows = rows(
                row("2026-04-21", 100),
                row("2026-04-22", 400),
                row("2026-04-23", -300)
        );
        DrawdownResponse r = PnlService.computeDrawdownFromRows(rows, 30, null);
        assertThat(r.maxDrawdownPct()).isEqualByComparingTo("-60");
        assertThat(r.baseline()).isNull();
    }

    @Test
    void prefersNetPnl_thenGross_thenRealized() {
        DailyPnlEntity a = new DailyPnlEntity();
        a.setTradingDate(LocalDate.of(2026,4,21));
        a.setNetPnl(new BigDecimal("100"));
        a.setGrossPnl(new BigDecimal("999")); // 不會用到
        DailyPnlEntity b = new DailyPnlEntity();
        b.setTradingDate(LocalDate.of(2026,4,22));
        b.setGrossPnl(new BigDecimal("200")); // netPnl null → fallback gross
        DailyPnlEntity c = new DailyPnlEntity();
        c.setTradingDate(LocalDate.of(2026,4,23));
        c.setRealizedPnl(new BigDecimal("-50")); // gross/net 都 null
        DrawdownResponse r = PnlService.computeDrawdownFromRows(List.of(a,b,c), 90, new BigDecimal("1000"));
        // equity: 100, 300, 250 → peak 300 @04-22, trough 250 @04-23, dd=50
        // 50/1000 = -5.0
        assertThat(r.maxDrawdownPct()).isEqualByComparingTo("-5");
        assertThat(r.peakAt()).isEqualTo(LocalDate.of(2026,4,22));
        assertThat(r.troughAt()).isEqualTo(LocalDate.of(2026,4,23));
    }

    // helpers
    private static DailyPnlEntity row(String date, double netPnl) {
        DailyPnlEntity e = new DailyPnlEntity();
        e.setTradingDate(LocalDate.parse(date));
        e.setNetPnl(BigDecimal.valueOf(netPnl));
        return e;
    }
    private static List<DailyPnlEntity> rows(DailyPnlEntity... arr) {
        List<DailyPnlEntity> list = new ArrayList<>();
        for (DailyPnlEntity r : arr) list.add(r);
        return list;
    }
}
