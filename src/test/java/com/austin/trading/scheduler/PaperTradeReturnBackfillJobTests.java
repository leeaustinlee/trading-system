package com.austin.trading.scheduler;

import com.austin.trading.entity.MarketIndexDailyEntity;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.repository.MarketIndexDailyRepository;
import com.austin.trading.repository.PaperTradeRepository;
import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0.6：PaperTradeReturnBackfillJob 行為驗證。
 *
 * <p>覆蓋條件：</p>
 * <ul>
 *   <li>1d / 3d / 5d / 10d offset 都會分別查 entry_date 對應日</li>
 *   <li>有 close → 計算 (close - entry) / entry × 100</li>
 *   <li>無 close → skip 該 cell（保持 null）</li>
 *   <li>已填過 → idempotent skip（不覆蓋）</li>
 *   <li>paper.return_backfill.enabled=false → 整個 job no-op</li>
 * </ul>
 */
class PaperTradeReturnBackfillJobTests {

    private PaperTradeRepository paperRepo;
    private MarketIndexDailyRepository idxRepo;
    private ScoreConfigService cfg;
    private PaperTradeReturnBackfillJob job;

    @BeforeEach
    @SuppressWarnings({"unchecked"})
    void setUp() {
        paperRepo = mock(PaperTradeRepository.class);
        idxRepo = mock(MarketIndexDailyRepository.class);
        cfg = mock(ScoreConfigService.class);
        ObjectProvider<ScoreConfigService> cfgProvider = mock(ObjectProvider.class);
        when(cfgProvider.getIfAvailable()).thenReturn(cfg);

        lenient().when(cfg.getBoolean(eq("paper.return_backfill.enabled"), anyBoolean()))
                .thenReturn(true);
        when(paperRepo.save(any(PaperTradeEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // default: empty list 對 unmatched dates
        lenient().when(paperRepo.findByEntryDate(any(LocalDate.class))).thenReturn(List.of());
        // P0.6c default：TAIEX 沒資料時 findTradingDatesBefore 回 empty（讓單獨 case 自己 stub）
        lenient().when(idxRepo.findTradingDatesBefore(eq("t00"), any(LocalDate.class), any(Pageable.class)))
                .thenReturn(List.of());

        job = new PaperTradeReturnBackfillJob(paperRepo, idxRepo, cfgProvider);
    }

    /** Helper：stub TAIEX 給定 N 個 trading_date，最近 N 個（descending）。 */
    @SuppressWarnings("unchecked")
    private void stubTaiexCalendar(LocalDate referenceDate, List<LocalDate> tradingDatesAsc) {
        // findTradingDatesBefore 預期 DESC (最新到最舊)
        List<LocalDate> desc = new java.util.ArrayList<>(tradingDatesAsc);
        java.util.Collections.reverse(desc);
        for (int n = 1; n <= desc.size(); n++) {
            int idx = n - 1;
            org.mockito.Mockito.lenient().when(idxRepo.findTradingDatesBefore(
                    eq("t00"), eq(referenceDate), org.mockito.ArgumentMatchers.argThat(p ->
                            p != null && p.getPageNumber() == idx && p.getPageSize() == 1)))
                    .thenReturn(List.of(desc.get(idx)));
        }
    }

    @Test
    void onePaperTrade_oneDayLater_close_returnComputed() {
        // P0.6c：用 TAIEX 找 ref 之前 1 個 trading_date → 4-29
        LocalDate ref = LocalDate.of(2026, 4, 30); // Thu
        LocalDate entry = LocalDate.of(2026, 4, 29); // Wed (TAIEX trading day)
        stubTaiexCalendar(ref, List.of(entry));

        PaperTradeEntity t = makeTrade("2330", entry, new BigDecimal("100.00"));
        when(paperRepo.findByEntryDate(entry)).thenReturn(List.of(t));
        when(idxRepo.findBySymbolAndTradingDate(eq("2330"), eq(ref)))
                .thenReturn(Optional.of(makeIdx("2330", ref, new BigDecimal("105.00"))));

        var summary = job.run(ref);
        assertThat(summary.filled1d()).isEqualTo(1);
        assertThat(t.getReturn1d()).isEqualByComparingTo("5.0000");
        verify(paperRepo, atLeastOnce()).save(t);
    }

    @Test
    void noClose_skip_fieldStaysNull() {
        LocalDate ref = LocalDate.of(2026, 4, 30);
        LocalDate entry = LocalDate.of(2026, 4, 29);
        stubTaiexCalendar(ref, List.of(entry));

        PaperTradeEntity t = makeTrade("9999", entry, new BigDecimal("50.00"));
        when(paperRepo.findByEntryDate(entry)).thenReturn(List.of(t));
        when(idxRepo.findBySymbolAndTradingDate(eq("9999"), any(LocalDate.class)))
                .thenReturn(Optional.empty());

        var summary = job.run(ref);
        assertThat(summary.filled1d()).isEqualTo(0);
        assertThat(t.getReturn1d()).isNull();
        verify(paperRepo, never()).save(t);
    }

    @Test
    void alreadyFilled_idempotentSkip() {
        LocalDate ref = LocalDate.of(2026, 4, 30); // Thu
        // 5 trading days before Thu 4-30 → Fri 4-23 (跳過週末)
        LocalDate entry = LocalDate.of(2026, 4, 23);
        // stub 5 個 trading_date，最舊的是 entry
        List<LocalDate> td = List.of(
                LocalDate.of(2026, 4, 23),
                LocalDate.of(2026, 4, 24),
                LocalDate.of(2026, 4, 27),
                LocalDate.of(2026, 4, 28),
                LocalDate.of(2026, 4, 29));
        stubTaiexCalendar(ref, td);

        PaperTradeEntity t = makeTrade("2330", entry, new BigDecimal("100.00"));
        t.setReturn5d(new BigDecimal("3.0000")); // already filled
        when(paperRepo.findByEntryDate(entry)).thenReturn(List.of(t));
        when(idxRepo.findBySymbolAndTradingDate(eq("2330"), eq(ref)))
                .thenReturn(Optional.of(makeIdx("2330", ref, new BigDecimal("110.00"))));

        var summary = job.run(ref);
        assertThat(summary.filled5d()).isEqualTo(0);
        assertThat(t.getReturn5d()).isEqualByComparingTo("3.0000");
        verify(paperRepo, never()).save(t);
    }

    @Test
    void disabled_noop() {
        when(cfg.getBoolean(eq("paper.return_backfill.enabled"), anyBoolean())).thenReturn(false);
        job.run(); // public schedule entry
        verify(paperRepo, never()).findByEntryDate(any(LocalDate.class));
    }

    @Test
    void allOffsets_oneSymbol_fillsCorrectly() {
        LocalDate ref = LocalDate.of(2026, 5, 10); // Sun (test 用日，stub 不論週末)
        // 模擬 10 個連續 trading day（不真模擬週末，純測試流程）
        // 4-26 + i where i=1..10 → 4-27, 4-28, 4-29, 4-30 後跨月，改用 plusDays
        List<LocalDate> td = new java.util.ArrayList<>();
        LocalDate base = LocalDate.of(2026, 4, 26);
        for (int i = 1; i <= 10; i++) td.add(base.plusDays(i));
        stubTaiexCalendar(ref, td);

        // 對 1d / 3d / 5d / 10d 各創一筆 trade
        LocalDate e1  = td.get(td.size() - 1);  // 最近 (5-6)
        LocalDate e3  = td.get(td.size() - 3);
        LocalDate e5  = td.get(td.size() - 5);
        LocalDate e10 = td.get(0);              // 最舊 (4-27)

        PaperTradeEntity t1  = makeTrade("2330", e1,  new BigDecimal("100.00"));
        PaperTradeEntity t3  = makeTrade("2330", e3,  new BigDecimal("100.00"));
        PaperTradeEntity t5  = makeTrade("2330", e5,  new BigDecimal("100.00"));
        PaperTradeEntity t10 = makeTrade("2330", e10, new BigDecimal("100.00"));
        when(paperRepo.findByEntryDate(e1)).thenReturn(List.of(t1));
        when(paperRepo.findByEntryDate(e3)).thenReturn(List.of(t3));
        when(paperRepo.findByEntryDate(e5)).thenReturn(List.of(t5));
        when(paperRepo.findByEntryDate(e10)).thenReturn(List.of(t10));

        when(idxRepo.findBySymbolAndTradingDate(eq("2330"), eq(ref)))
                .thenReturn(Optional.of(makeIdx("2330", ref, new BigDecimal("108.00"))));

        var summary = job.run(ref);
        assertThat(summary.filled1d()).isEqualTo(1);
        assertThat(summary.filled3d()).isEqualTo(1);
        assertThat(summary.filled5d()).isEqualTo(1);
        assertThat(summary.filled10d()).isEqualTo(1);

        assertThat(t1.getReturn1d()).isEqualByComparingTo("8.0000");
        assertThat(t3.getReturn3d()).isEqualByComparingTo("8.0000");
        assertThat(t5.getReturn5d()).isEqualByComparingTo("8.0000");
        assertThat(t10.getReturn10d()).isEqualByComparingTo("8.0000");
    }

    @Test
    void zeroEntryPrice_skip() {
        LocalDate ref = LocalDate.of(2026, 4, 30);
        LocalDate entry = LocalDate.of(2026, 4, 29);
        stubTaiexCalendar(ref, List.of(entry));

        PaperTradeEntity t = makeTrade("2330", entry, BigDecimal.ZERO);
        when(paperRepo.findByEntryDate(entry)).thenReturn(List.of(t));

        var summary = job.run(ref);
        assertThat(summary.filled1d()).isEqualTo(0);
        verify(paperRepo, never()).save(t);
    }

    /**
     * P0.6c：核心驗收 — 週五 entry 的 1d return 走交易日邏輯應找到下週一，不是週六。
     */
    @Test
    void p06c_friday_entry_1d_uses_next_trading_day_not_saturday() {
        LocalDate ref = LocalDate.of(2026, 5, 4);  // Mon
        LocalDate friEntry = LocalDate.of(2026, 5, 1); // Fri
        // TAIEX 在 ref 之前的第 1 個 trading_date 是 Fri 5-1（不是 Sat 5-2 — 因為 5-2 沒 TAIEX 資料）
        stubTaiexCalendar(ref, List.of(friEntry));

        PaperTradeEntity t = makeTrade("2330", friEntry, new BigDecimal("100.00"));
        when(paperRepo.findByEntryDate(friEntry)).thenReturn(List.of(t));
        when(idxRepo.findBySymbolAndTradingDate(eq("2330"), eq(ref)))
                .thenReturn(Optional.of(makeIdx("2330", ref, new BigDecimal("103.00"))));

        var summary = job.run(ref);
        assertThat(summary.filled1d()).isEqualTo(1);
        assertThat(t.getReturn1d()).isEqualByComparingTo("3.0000");
    }

    @Test
    void p06c_taiex_history_insufficient_returns_zero() {
        LocalDate ref = LocalDate.of(2026, 4, 30);
        // 沒 stub TAIEX → setUp 預設 empty
        var summary = job.run(ref);
        assertThat(summary.filled1d()).isEqualTo(0);
        assertThat(summary.filled3d()).isEqualTo(0);
        assertThat(summary.filled5d()).isEqualTo(0);
        assertThat(summary.filled10d()).isEqualTo(0);
        verify(paperRepo, never()).findByEntryDate(any(LocalDate.class));
    }

    // ── helpers ─────────────────────────────────────────────

    private PaperTradeEntity makeTrade(String symbol, LocalDate entryDate, BigDecimal entryPrice) {
        PaperTradeEntity t = new PaperTradeEntity();
        t.setSymbol(symbol);
        t.setEntryDate(entryDate);
        t.setEntryPrice(entryPrice);
        t.setStatus("OPEN");
        t.setShadow(true);
        return t;
    }

    private MarketIndexDailyEntity makeIdx(String symbol, LocalDate date, BigDecimal close) {
        MarketIndexDailyEntity e = new MarketIndexDailyEntity();
        e.setSymbol(symbol);
        e.setTradingDate(date);
        e.setClosePrice(close);
        return e;
    }
}
