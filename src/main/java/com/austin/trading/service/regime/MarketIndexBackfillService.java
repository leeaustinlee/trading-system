package com.austin.trading.service.regime;

import com.austin.trading.client.TwseHistoryClient;
import com.austin.trading.client.TwseHistoryClient.DailyBar;
import com.austin.trading.entity.MarketIndexDailyEntity;
import com.austin.trading.repository.MarketIndexDailyRepository;
import com.austin.trading.service.ScoreConfigService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * P0.5 — TWSE TAIEX + 半導體代理（預設 2330）歷史回補與每日 upsert 服務。
 *
 * <p>本服務承擔兩個職責：</p>
 * <ol>
 *   <li><b>Startup auto-backfill</b>：若 {@code market_index_daily} 內某 symbol
 *       筆數不足（&lt; 30），自動抓過去 N 天（{@code market_regime.backfill_history_days}，
 *       預設 90 自然日 ≈ 60 交易日）的歷史落盤；可由
 *       {@code market_regime.auto_backfill_on_startup=false} 關閉。</li>
 *   <li><b>每日 upsert</b>：被 {@link com.austin.trading.scheduler.MarketIndexDataPrepJob}
 *       於盤後 15:30 呼叫，補抓最近 ~2 個月日線（避免 TWSE 補/改資料導致 stale）。</li>
 * </ol>
 *
 * <p>設計原則：</p>
 * <ul>
 *   <li>fail-safe：抓不到 → log warn，不丟例外，不清表（保留歷史）。</li>
 *   <li>throttle：每月 API 之間至少 sleep
 *       {@code market_regime.twse_throttle_ms}（預設 250ms）避免被 rate limit。</li>
 *   <li>idempotent upsert：同 (symbol, date) 已存在 → 更新欄位；不存在 → 新增。</li>
 * </ul>
 */
@Service
public class MarketIndexBackfillService {

    private static final Logger log = LoggerFactory.getLogger(MarketIndexBackfillService.class);

    static final String TAIEX_SYMBOL = "t00";

    static final String CFG_AUTO_BACKFILL_ENABLED  = "market_regime.auto_backfill_on_startup";
    static final String CFG_SEMI_PROXY_SYMBOL      = "market_regime.semi_proxy_symbol";
    static final String CFG_BACKFILL_DAYS          = "market_regime.backfill_history_days";
    static final String CFG_THROTTLE_MS            = "market_regime.twse_throttle_ms";

    /** Backfill 視為「歷史足夠」的最低筆數門檻；低於這個會觸發 startup 自動補。 */
    static final long BACKFILL_TRIGGER_THRESHOLD = 30L;

    private final TwseHistoryClient          twseHistoryClient;
    private final MarketIndexDailyRepository repository;
    private final ScoreConfigService         scoreConfig;

    /**
     * Spring-side kill switch — 預設 true，整合測試 / 離線開發環境可在 yaml 設 false
     * 避免 startup 時撥打 TWSE 拖慢 context 啟動。
     */
    @Value("${trading.market-index.startup-backfill.enabled:true}")
    private boolean startupBackfillEnabledSpring;

    public MarketIndexBackfillService(TwseHistoryClient twseHistoryClient,
                                      MarketIndexDailyRepository repository,
                                      ScoreConfigService scoreConfig) {
        this.twseHistoryClient = twseHistoryClient;
        this.repository        = repository;
        this.scoreConfig       = scoreConfig;
    }

    /**
     * Spring 啟動完成後檢查 DB 是否需要 backfill。
     *
     * <p>只在 {@code auto_backfill_on_startup=true}（預設）且任一 symbol 筆數
     * 不足時觸發；DB 已飽和的場景幾乎是 no-op（一次 count + 條件分支）。</p>
     */
    @PostConstruct
    public void backfillOnStartupIfNeeded() {
        if (!startupBackfillEnabledSpring) {
            log.info("[MarketIndexBackfill] startup backfill 已被 Spring 屬性停用 "
                    + "(trading.market-index.startup-backfill.enabled=false)");
            return;
        }
        boolean enabled = scoreConfig.getBoolean(CFG_AUTO_BACKFILL_ENABLED, true);
        if (!enabled) {
            log.info("[MarketIndexBackfill] startup backfill 已停用 (flag={})", CFG_AUTO_BACKFILL_ENABLED);
            return;
        }
        String semi = scoreConfig.getString(CFG_SEMI_PROXY_SYMBOL, "2330");
        try {
            long taiexCount = repository.countBySymbol(TAIEX_SYMBOL);
            long semiCount  = repository.countBySymbol(semi);
            log.info("[MarketIndexBackfill] startup count check: TAIEX={} {}={}",
                    taiexCount, semi, semiCount);
            boolean needBackfill = taiexCount < BACKFILL_TRIGGER_THRESHOLD
                                || semiCount  < BACKFILL_TRIGGER_THRESHOLD;
            if (!needBackfill) {
                log.info("[MarketIndexBackfill] DB 歷史已足夠 (>= {} 筆 / symbol)，略過 backfill",
                        BACKFILL_TRIGGER_THRESHOLD);
                return;
            }
            int days = Math.max(60, scoreConfig.getInt(CFG_BACKFILL_DAYS, 90));
            log.info("[MarketIndexBackfill] 開始 backfill {} 自然日 (TAIEX + {})", days, semi);
            int total = backfillRange(LocalDate.now().minusDays(days), LocalDate.now(), semi);
            log.info("[MarketIndexBackfill] startup backfill 完成，共 upsert {} 筆", total);
        } catch (Exception e) {
            log.warn("[MarketIndexBackfill] startup backfill 失敗（不阻擋啟動）: {}", e.getMessage(), e);
        }
    }

    /**
     * 抓 {@code [from, to]} 區間內的所有月份（兩個 symbol 各跑一次），upsert 至 DB。
     *
     * @return 實際 upsert 的筆數（新增 + 更新）
     */
    public int backfillRange(LocalDate from, LocalDate to, String semiSymbol) {
        if (from == null || to == null || from.isAfter(to)) return 0;
        int throttle = Math.max(0, scoreConfig.getInt(CFG_THROTTLE_MS, 250));

        int upserted = 0;
        YearMonth start = YearMonth.from(from);
        YearMonth end   = YearMonth.from(to);

        // TAIEX
        for (YearMonth ym = start; !ym.isAfter(end); ym = ym.plusMonths(1)) {
            List<DailyBar> bars = twseHistoryClient.fetchTaiexMonth(ym);
            upserted += upsertBars(bars, from, to);
            sleepQuietly(throttle);
        }
        // SEMI proxy（個股 STOCK_DAY）
        if (semiSymbol != null && !semiSymbol.isBlank() && !TAIEX_SYMBOL.equals(semiSymbol)) {
            for (YearMonth ym = start; !ym.isAfter(end); ym = ym.plusMonths(1)) {
                List<DailyBar> bars = twseHistoryClient.fetchStockMonth(semiSymbol, ym);
                upserted += upsertBars(bars, from, to);
                sleepQuietly(throttle);
            }
        }
        return upserted;
    }

    /**
     * 每日 prep job 入口：抓最近 2 個月（含本月）的 TAIEX 與 semi proxy 日線，upsert。
     *
     * <p>2 個月足以涵蓋 60 個交易日的 MA 計算，又不會每天打太多次 API。</p>
     */
    public int dailyRefresh(LocalDate asOf) {
        if (asOf == null) asOf = LocalDate.now();
        String semi = scoreConfig.getString(CFG_SEMI_PROXY_SYMBOL, "2330");
        return backfillRange(asOf.minusMonths(2).withDayOfMonth(1), asOf, semi);
    }

    // ── upsert ────────────────────────────────────────────────────────────

    /**
     * 對單批 bar 做 upsert。每個 {@code repository.save()} 是 Spring Data 自帶的
     * 獨立交易；單筆 save 例外不影響其他 bar，符合本服務 fail-safe 哲學。
     */
    int upsertBars(List<DailyBar> bars, LocalDate from, LocalDate to) {
        if (bars == null || bars.isEmpty()) return 0;
        int n = 0;
        for (DailyBar bar : bars) {
            if (bar == null || bar.tradingDate() == null) continue;
            // 月度 API 會回整月；過濾到 [from, to]
            if (bar.tradingDate().isBefore(from) || bar.tradingDate().isAfter(to)) continue;
            if (bar.close() == null) continue;
            try {
                MarketIndexDailyEntity e = repository
                        .findBySymbolAndTradingDate(bar.symbol(), bar.tradingDate())
                        .orElseGet(MarketIndexDailyEntity::new);
                e.setSymbol(bar.symbol());
                e.setTradingDate(bar.tradingDate());
                e.setOpenPrice(bar.open());
                e.setHighPrice(bar.high());
                e.setLowPrice(bar.low());
                e.setClosePrice(bar.close());
                e.setVolume(bar.volume());
                repository.save(e);
                n++;
            } catch (Exception ex) {
                log.warn("[MarketIndexBackfill] upsert {} {} 失敗: {}",
                        bar.symbol(), bar.tradingDate(), ex.getMessage());
            }
        }
        return n;
    }

    private void sleepQuietly(int millis) {
        if (millis <= 0) return;
        try { Thread.sleep(millis); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
