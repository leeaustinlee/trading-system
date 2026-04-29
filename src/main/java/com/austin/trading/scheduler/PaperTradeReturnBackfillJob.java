package com.austin.trading.scheduler;

import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.repository.MarketIndexDailyRepository;
import com.austin.trading.repository.PaperTradeRepository;
import com.austin.trading.service.ScoreConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * P0.6 (2026-04-29) Phase 1 forward-testing：每日盤後回填 paper_trade.return_1d / 3d / 5d / 10d。
 *
 * <p>邏輯：對每筆 paper_trade（含 shadow / live、OPEN / CLOSED 都算），如果它的進場日剛好
 * 是 1/3/5/10 個交易日前，就用「進場日的 close 價（從 market_index_daily 拿）」對比
 * 「進場時的 entry_price」算 % return。</p>
 *
 * <p>這個 job 只用個股歷史日線（{@link MarketIndexDailyRepository} 抓 P0.5 的 market_index_daily 表），
 * 因此前 60 天 backfill（{@code MarketIndexBackfillService}）涵蓋的 symbol 才有資料。
 * 沒抓到的 symbol 該 cell 維持 null，下次再試（job 是 idempotent）。</p>
 *
 * <p>cron 18:30（盤後 + WatchlistRefresh + T86 之後）。</p>
 *
 * <p><b>P0.6c：用交易日（不是自然日）算 offset</b>。週五 entry 的 1d return 應該是下週一的 close，
 * 不是週六。實作走 TAIEX (`t00`) 的歷史 trading_date 當 calendar：找「referenceDate 之前的第 N 個
 * trading_date」當作 entry_date，再用 referenceDate 的 close 算報酬。</p>
 */
@Component
public class PaperTradeReturnBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(PaperTradeReturnBackfillJob.class);

    private final PaperTradeRepository paperTradeRepository;
    private final MarketIndexDailyRepository marketIndexRepository;
    private final ObjectProvider<ScoreConfigService> scoreConfigProvider;

    public PaperTradeReturnBackfillJob(
            PaperTradeRepository paperTradeRepository,
            MarketIndexDailyRepository marketIndexRepository,
            ObjectProvider<ScoreConfigService> scoreConfigProvider) {
        this.paperTradeRepository = paperTradeRepository;
        this.marketIndexRepository = marketIndexRepository;
        this.scoreConfigProvider = scoreConfigProvider;
    }

    @Scheduled(cron = "${trading.scheduler.paper-return-backfill-cron:0 30 18 * * MON-FRI}",
               zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        if (!isEnabled()) {
            log.debug("[PaperReturnBackfill] disabled, skip");
            return;
        }
        run(LocalDate.now());
    }

    /** Test 友善的入口（傳入 reference date）。 */
    public BackfillSummary run(LocalDate referenceDate) {
        int filled1d = backfillForOffset(referenceDate, 1);
        int filled3d = backfillForOffset(referenceDate, 3);
        int filled5d = backfillForOffset(referenceDate, 5);
        int filled10d = backfillForOffset(referenceDate, 10);

        log.info("[PaperReturnBackfill] reference={} filled 1d={} 3d={} 5d={} 10d={}",
                referenceDate, filled1d, filled3d, filled5d, filled10d);
        return new BackfillSummary(filled1d, filled3d, filled5d, filled10d);
    }

    /**
     * 回填某個 offset：用 TAIEX 找 referenceDate 之前第 N 個 trading_date 當 entry_date，
     * 撈該日的 paper_trade，用 referenceDate 的 close 計算 (close - entry_price) / entry_price。
     *
     * <p>P0.6c：找不到 N 個 trading_date（例如系統剛上線、TAIEX 歷史 < N 筆）→ 回 0，下次再試。</p>
     */
    private int backfillForOffset(LocalDate referenceDate, int offsetDays) {
        Optional<LocalDate> entryDateOpt = findTradingDayNBefore(referenceDate, offsetDays);
        if (entryDateOpt.isEmpty()) {
            log.debug("[PaperReturnBackfill] no TAIEX trading_date {} days before {}, skip offset",
                    offsetDays, referenceDate);
            return 0;
        }
        LocalDate entryDate = entryDateOpt.get();

        List<PaperTradeEntity> trades = paperTradeRepository.findByEntryDate(entryDate);
        int filled = 0;
        for (PaperTradeEntity t : trades) {
            BigDecimal pct = computeReturnPct(t, referenceDate);
            if (pct == null) continue;

            BigDecimal existing = readReturnField(t, offsetDays);
            if (existing != null) continue; // idempotent: 已填過就不覆蓋

            writeReturnField(t, offsetDays, pct);
            paperTradeRepository.save(t);
            filled++;
        }
        return filled;
    }

    /**
     * P0.6c: 用 TAIEX 找 referenceDate 之前第 N 個 trading_date（跳過週末 / 國定假）。
     * 找不到（系統剛上線、TAIEX 歷史 &lt; N 筆）→ 回 empty。
     */
    private Optional<LocalDate> findTradingDayNBefore(LocalDate referenceDate, int n) {
        if (n <= 0) return Optional.empty();
        List<LocalDate> dates = marketIndexRepository.findTradingDatesBefore(
                "t00", referenceDate, PageRequest.of(n - 1, 1));
        return dates.isEmpty() ? Optional.empty() : Optional.of(dates.get(0));
    }

    /**
     * 依 paper_trade.symbol + market_index_daily 算 referenceDate 的 close vs entry_price。
     * 拿不到 close 或 entry_price 為 0 → 回 null（保持欄位 null，下次再試）。
     */
    private BigDecimal computeReturnPct(PaperTradeEntity t, LocalDate referenceDate) {
        if (t == null || t.getSymbol() == null) return null;
        BigDecimal entry = t.getEntryPrice();
        if (entry == null || entry.signum() == 0) return null;

        Optional<BigDecimal> closeOpt = marketIndexRepository
                .findBySymbolAndTradingDate(t.getSymbol(), referenceDate)
                .map(row -> row.getClosePrice());
        if (closeOpt.isEmpty()) {
            log.debug("[PaperReturnBackfill] no close for symbol={} date={}, skip",
                    t.getSymbol(), referenceDate);
            return null;
        }
        BigDecimal close = closeOpt.get();
        if (close == null || close.signum() == 0) return null;

        // (close - entry) / entry × 100
        return close.subtract(entry)
                .divide(entry, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal readReturnField(PaperTradeEntity t, int offsetDays) {
        return switch (offsetDays) {
            case 1  -> t.getReturn1d();
            case 3  -> t.getReturn3d();
            case 5  -> t.getReturn5d();
            case 10 -> t.getReturn10d();
            default -> null;
        };
    }

    private void writeReturnField(PaperTradeEntity t, int offsetDays, BigDecimal pct) {
        switch (offsetDays) {
            case 1  -> t.setReturn1d(pct);
            case 3  -> t.setReturn3d(pct);
            case 5  -> t.setReturn5d(pct);
            case 10 -> t.setReturn10d(pct);
            default -> { /* no-op */ }
        }
    }

    private boolean isEnabled() {
        ScoreConfigService cfg = scoreConfigProvider != null ? scoreConfigProvider.getIfAvailable() : null;
        if (cfg == null) return true;
        return cfg.getBoolean("paper.return_backfill.enabled", true);
    }

    public record BackfillSummary(int filled1d, int filled3d, int filled5d, int filled10d) { }
}
