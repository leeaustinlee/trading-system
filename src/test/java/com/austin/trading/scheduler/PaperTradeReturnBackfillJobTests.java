package com.austin.trading.scheduler;

import com.austin.trading.entity.MarketIndexDailyEntity;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.repository.MarketIndexDailyRepository;
import com.austin.trading.repository.PaperTradeRepository;
import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

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
        when(paperRepo.findByEntryDate(any(LocalDate.class))).thenReturn(List.of());

        job = new PaperTradeReturnBackfillJob(paperRepo, idxRepo, cfgProvider);
    }

    @Test
    void onePaperTrade_oneDayLater_close_returnComputed() {
        LocalDate ref = LocalDate.of(2026, 4, 30);
        LocalDate entry = ref.minusDays(1);

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
        LocalDate entry = ref.minusDays(1);

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
        LocalDate ref = LocalDate.of(2026, 4, 30);
        LocalDate entry = ref.minusDays(5);

        PaperTradeEntity t = makeTrade("2330", entry, new BigDecimal("100.00"));
        t.setReturn5d(new BigDecimal("3.0000")); // already filled
        when(paperRepo.findByEntryDate(entry)).thenReturn(List.of(t));

        when(idxRepo.findBySymbolAndTradingDate(eq("2330"), eq(ref)))
                .thenReturn(Optional.of(makeIdx("2330", ref, new BigDecimal("110.00"))));

        var summary = job.run(ref);
        // 5d 已填過 → skip
        assertThat(summary.filled5d()).isEqualTo(0);
        // 但 return_5d 應保留為 3.0000，不被 close 110 (= +10%) 覆蓋
        assertThat(t.getReturn5d()).isEqualByComparingTo("3.0000");
        verify(paperRepo, never()).save(t);
    }

    @Test
    void disabled_noop() {
        when(cfg.getBoolean(eq("paper.return_backfill.enabled"), anyBoolean())).thenReturn(false);

        // direct schedule entry — call the public run()
        job.run();

        // public run() 不傳 ref date；不會碰 repository
        verify(paperRepo, never()).findByEntryDate(any(LocalDate.class));
    }

    @Test
    void allOffsets_oneSymbol_fillsCorrectly() {
        LocalDate ref = LocalDate.of(2026, 5, 10);

        PaperTradeEntity t1 = makeTrade("2330", ref.minusDays(1),  new BigDecimal("100.00"));
        PaperTradeEntity t3 = makeTrade("2330", ref.minusDays(3),  new BigDecimal("100.00"));
        PaperTradeEntity t5 = makeTrade("2330", ref.minusDays(5),  new BigDecimal("100.00"));
        PaperTradeEntity t10= makeTrade("2330", ref.minusDays(10), new BigDecimal("100.00"));

        when(paperRepo.findByEntryDate(ref.minusDays(1))).thenReturn(List.of(t1));
        when(paperRepo.findByEntryDate(ref.minusDays(3))).thenReturn(List.of(t3));
        when(paperRepo.findByEntryDate(ref.minusDays(5))).thenReturn(List.of(t5));
        when(paperRepo.findByEntryDate(ref.minusDays(10))).thenReturn(List.of(t10));

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
        PaperTradeEntity t = makeTrade("2330", ref.minusDays(1), BigDecimal.ZERO);
        when(paperRepo.findByEntryDate(ref.minusDays(1))).thenReturn(List.of(t));

        var summary = job.run(ref);
        assertThat(summary.filled1d()).isEqualTo(0);
        verify(paperRepo, never()).save(t);
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
