package com.austin.trading.service.regime;

import com.austin.trading.client.TwseHistoryClient;
import com.austin.trading.client.TwseHistoryClient.DailyBar;
import com.austin.trading.entity.MarketIndexDailyEntity;
import com.austin.trading.repository.MarketIndexDailyRepository;
import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0.5 — {@link MarketIndexBackfillService} 單元測試。
 *
 * <p>聚焦在「不真打 TWSE」的核心邏輯：upsert / 範圍過濾 / 容錯 / startup 條件分支。</p>
 */
class MarketIndexBackfillServiceTests {

    private final TwseHistoryClient          client = mock(TwseHistoryClient.class);
    private final MarketIndexDailyRepository repo   = mock(MarketIndexDailyRepository.class);
    private final ScoreConfigService         cfg    = mock(ScoreConfigService.class);

    private MarketIndexBackfillService svc() {
        when(cfg.getBoolean(eq(MarketIndexBackfillService.CFG_AUTO_BACKFILL_ENABLED), anyBoolean()))
                .thenReturn(true);
        when(cfg.getString(eq(MarketIndexBackfillService.CFG_SEMI_PROXY_SYMBOL), anyString()))
                .thenReturn("2330");
        when(cfg.getInt(eq(MarketIndexBackfillService.CFG_BACKFILL_DAYS), anyInt())).thenReturn(60);
        when(cfg.getInt(eq(MarketIndexBackfillService.CFG_THROTTLE_MS),  anyInt())).thenReturn(0);
        MarketIndexBackfillService s = new MarketIndexBackfillService(client, repo, cfg);
        // @Value 在純 mock 下不會被注入，這裡用 ReflectionTestUtils 強制 = true
        ReflectionTestUtils.setField(s, "startupBackfillEnabledSpring", true);
        return s;
    }

    @Test
    void dailyRefresh_apiReturnsEmpty_doesNotCrashAndUpsertsZero() {
        when(client.fetchTaiexMonth(any(YearMonth.class))).thenReturn(List.of());
        when(client.fetchStockMonth(eq("2330"), any(YearMonth.class))).thenReturn(List.of());

        int n = svc().dailyRefresh(LocalDate.of(2026, 4, 28));

        assertThat(n).isEqualTo(0);
        verify(repo, never()).save(any(MarketIndexDailyEntity.class));
    }

    @Test
    void dailyRefresh_upsertsTaiexAndSemiBars_inRange() {
        LocalDate asOf = LocalDate.of(2026, 4, 28);
        // TAIEX 兩筆：一筆在 [from, to] 範圍內，一筆超出 → 只算前者
        when(client.fetchTaiexMonth(any(YearMonth.class))).thenReturn(List.of(
                new DailyBar("t00", LocalDate.of(2026, 4, 25),
                        new BigDecimal("19400"), new BigDecimal("19500"),
                        new BigDecimal("19350"), new BigDecimal("19450"), null),
                new DailyBar("t00", LocalDate.of(2025, 1, 1), // 超出 dailyRefresh from 範圍
                        null, null, null, new BigDecimal("18000"), null)
        ));
        when(client.fetchStockMonth(eq("2330"), any(YearMonth.class))).thenReturn(List.of(
                new DailyBar("2330", LocalDate.of(2026, 4, 25),
                        new BigDecimal("1180"), new BigDecimal("1200"),
                        new BigDecimal("1175"), new BigDecimal("1190"), 25_000_000L)
        ));
        when(repo.findBySymbolAndTradingDate(anyString(), any(LocalDate.class)))
                .thenReturn(Optional.empty());

        int n = svc().dailyRefresh(asOf);

        // dailyRefresh 走 (asOf - 2 月起的整月 → asOf 當月) ≈ 3 個月份；
        // TAIEX 與 2330 各被呼叫 3 次，每次 mock 都回同一 bar → upsert 被多次觸發
        // 但因 idempotent 故最終 close 一致；本 case 只驗「有打到、有 save」。
        verify(client, atLeastOnce()).fetchTaiexMonth(any(YearMonth.class));
        verify(client, atLeastOnce()).fetchStockMonth(eq("2330"), any(YearMonth.class));
        assertThat(n).isGreaterThanOrEqualTo(2);
        verify(repo, atLeastOnce()).save(any(MarketIndexDailyEntity.class));
    }

    @Test
    void dailyRefresh_skipsBarWithNullClose() {
        when(client.fetchTaiexMonth(any(YearMonth.class))).thenReturn(List.of(
                new DailyBar("t00", LocalDate.of(2026, 4, 25),
                        null, null, null, null /* close=null */, null)
        ));
        when(client.fetchStockMonth(eq("2330"), any(YearMonth.class))).thenReturn(List.of());

        int n = svc().dailyRefresh(LocalDate.of(2026, 4, 28));

        assertThat(n).isEqualTo(0);
        verify(repo, never()).save(any(MarketIndexDailyEntity.class));
    }

    @Test
    void dailyRefresh_existingRowIsUpdatedInsteadOfInserted() {
        LocalDate d = LocalDate.of(2026, 4, 25);
        MarketIndexDailyEntity existing = new MarketIndexDailyEntity(
                "t00", d, new BigDecimal("18000"), new BigDecimal("18500"),
                new BigDecimal("17900"), new BigDecimal("18200"), null);
        when(client.fetchTaiexMonth(any(YearMonth.class))).thenReturn(List.of(
                new DailyBar("t00", d,
                        new BigDecimal("19400"), new BigDecimal("19500"),
                        new BigDecimal("19350"), new BigDecimal("19450"), null)
        ));
        when(client.fetchStockMonth(eq("2330"), any(YearMonth.class))).thenReturn(List.of());
        when(repo.findBySymbolAndTradingDate(eq("t00"), eq(d)))
                .thenReturn(Optional.of(existing));

        svc().dailyRefresh(LocalDate.of(2026, 4, 28));

        // 同一個 entity 物件被更新（close 從 18200 → 19450）然後 save
        assertThat(existing.getClosePrice()).isEqualTo(new BigDecimal("19450"));
        verify(repo, atLeastOnce()).save(eq(existing));
    }

    @Test
    void backfillOnStartupIfNeeded_dbAlreadyHasEnough_isNoOp() {
        when(repo.countBySymbol(MarketIndexBackfillService.TAIEX_SYMBOL)).thenReturn(60L);
        when(repo.countBySymbol("2330")).thenReturn(60L);

        svc().backfillOnStartupIfNeeded();

        verify(client, never()).fetchTaiexMonth(any(YearMonth.class));
        verify(client, never()).fetchStockMonth(anyString(), any(YearMonth.class));
    }

    @Test
    void backfillOnStartupIfNeeded_emptyDb_triggersBackfill() {
        when(repo.countBySymbol(MarketIndexBackfillService.TAIEX_SYMBOL)).thenReturn(0L);
        when(repo.countBySymbol("2330")).thenReturn(0L);
        when(client.fetchTaiexMonth(any(YearMonth.class))).thenReturn(List.of());
        when(client.fetchStockMonth(anyString(), any(YearMonth.class))).thenReturn(List.of());

        svc().backfillOnStartupIfNeeded();

        verify(client, atLeastOnce()).fetchTaiexMonth(any(YearMonth.class));
        verify(client, atLeastOnce()).fetchStockMonth(eq("2330"), any(YearMonth.class));
    }

    @Test
    void backfillOnStartupIfNeeded_disabled_isNoOp() {
        when(cfg.getBoolean(eq(MarketIndexBackfillService.CFG_AUTO_BACKFILL_ENABLED), anyBoolean()))
                .thenReturn(false);
        // 即使 DB 是空，flag = false 也不該抓
        when(repo.countBySymbol(anyString())).thenReturn(0L);

        // 用「禁用」的 mock 重新組裝 svc（不能用 svc()，因為它強制設 true）
        MarketIndexBackfillService disabled =
                new MarketIndexBackfillService(client, repo, cfg);
        // 模擬 Spring @Value 解析過：trading.market-index.startup-backfill.enabled=true
        // 才能進入第二層 score_config 判斷（由 mock 設為 false）
        ReflectionTestUtils.setField(disabled, "startupBackfillEnabledSpring", true);
        disabled.backfillOnStartupIfNeeded();

        verify(client, never()).fetchTaiexMonth(any(YearMonth.class));
    }

    @Test
    void backfillOnStartupIfNeeded_springSwitchOff_isNoOp() {
        MarketIndexBackfillService springOff =
                new MarketIndexBackfillService(client, repo, cfg);
        // 預設 Spring @Value = false（field default for boolean primitive）
        // 即使 score_config flag = true，也應該短路。
        ReflectionTestUtils.setField(springOff, "startupBackfillEnabledSpring", false);
        springOff.backfillOnStartupIfNeeded();
        verify(repo, never()).countBySymbol(anyString());
        verify(client, never()).fetchTaiexMonth(any(YearMonth.class));
    }

    @Test
    void backfillOnStartupIfNeeded_clientThrows_doesNotPropagate() {
        when(repo.countBySymbol(anyString())).thenReturn(0L);
        when(client.fetchTaiexMonth(any(YearMonth.class)))
                .thenThrow(new RuntimeException("TWSE down"));
        when(client.fetchStockMonth(anyString(), any(YearMonth.class)))
                .thenReturn(List.of());

        // 不可丟例外，否則整個 ApplicationContext 會啟動失敗
        svc().backfillOnStartupIfNeeded();
    }

    @Test
    void backfillRange_fromAfterTo_returnsZero() {
        int n = svc().backfillRange(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 4, 1), "2330");
        assertThat(n).isEqualTo(0);
        verify(client, never()).fetchTaiexMonth(any(YearMonth.class));
    }

    @Test
    void backfillRange_throttleSleeps_areInvokedPerMonth() {
        // throttle = 0 (svc()) 確保測試不會慢，這裡只驗 client 月度呼叫次數
        when(client.fetchTaiexMonth(any(YearMonth.class))).thenReturn(List.of());
        when(client.fetchStockMonth(eq("2330"), any(YearMonth.class))).thenReturn(List.of());
        AtomicInteger taiexCalls = new AtomicInteger();
        when(client.fetchTaiexMonth(any(YearMonth.class))).thenAnswer(inv -> {
            taiexCalls.incrementAndGet();
            return List.of();
        });

        svc().backfillRange(
                LocalDate.of(2026, 1, 15),
                LocalDate.of(2026, 4, 28),
                "2330");
        // 1/2/3/4 月共 4 個月份
        assertThat(taiexCalls.get()).isEqualTo(4);
        verify(client, times(4)).fetchStockMonth(eq("2330"), any(YearMonth.class));
    }
}
