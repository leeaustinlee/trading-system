package com.austin.trading.service.regime;

import com.austin.trading.entity.MarketIndexDailyEntity;
import com.austin.trading.repository.MarketIndexDailyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0.5 — {@link TwseMarketIndexProvider} 單元測試。
 *
 * <p>覆蓋四個關鍵行為：</p>
 * <ul>
 *   <li>DB 為空 → 返回空 list（fail-safe）</li>
 *   <li>DB desc 排序 → 反轉成 oldest-first</li>
 *   <li>{@code getTaiex60DayMa} 不夠 60 筆回 empty</li>
 *   <li>{@code getTaiex60DayMa} 有 60 筆時計算正確平均</li>
 * </ul>
 */
class TwseMarketIndexProviderTests {

    private final MarketIndexDailyRepository repo = mock(MarketIndexDailyRepository.class);
    private final TwseMarketIndexProvider    provider = new TwseMarketIndexProvider(repo);

    @Test
    void getTaiexCloses_emptyDb_returnsEmptyList() {
        when(repo.findLatestBySymbolBefore(eq("t00"), any(LocalDate.class), any(Pageable.class)))
                .thenReturn(List.of());

        List<BigDecimal> closes = provider.getTaiexCloses(LocalDate.of(2026, 4, 28), 5);

        assertThat(closes).isEmpty();
    }

    @Test
    void getTaiexCloses_descFromRepo_returnsAscOldestFirst() {
        // Repo 回傳 desc：[28日, 27日, 26日]
        List<MarketIndexDailyEntity> desc = List.of(
                bar("t00", LocalDate.of(2026, 4, 28), new BigDecimal("19500")),
                bar("t00", LocalDate.of(2026, 4, 27), new BigDecimal("19400")),
                bar("t00", LocalDate.of(2026, 4, 26), new BigDecimal("19300"))
        );
        when(repo.findLatestBySymbolBefore(eq("t00"), any(LocalDate.class), any(Pageable.class)))
                .thenReturn(desc);

        List<BigDecimal> closes = provider.getTaiexCloses(LocalDate.of(2026, 4, 28), 3);

        // Provider 應該反轉成 oldest-first：[26日, 27日, 28日]
        assertThat(closes).containsExactly(
                new BigDecimal("19300"),
                new BigDecimal("19400"),
                new BigDecimal("19500")
        );
    }

    @Test
    void getSymbolCloses_invalidArgs_returnEmpty() {
        assertThat(provider.getSymbolCloses(null, LocalDate.now(), 5)).isEmpty();
        assertThat(provider.getSymbolCloses("",   LocalDate.now(), 5)).isEmpty();
        assertThat(provider.getSymbolCloses("2330", null,           5)).isEmpty();
        assertThat(provider.getSymbolCloses("2330", LocalDate.now(), 0)).isEmpty();
    }

    @Test
    void getSymbolCloses_repoThrows_returnsEmptyFailSafe() {
        when(repo.findLatestBySymbolBefore(eq("2330"), any(LocalDate.class), any(Pageable.class)))
                .thenThrow(new RuntimeException("DB connection lost"));

        // fail-safe: 不向上拋例外
        List<BigDecimal> closes = provider.getSymbolCloses("2330", LocalDate.now(), 5);
        assertThat(closes).isEmpty();
    }

    @Test
    void getTaiex60DayMa_under60Closes_returnsEmpty() {
        // 只回 30 筆 → 不夠 60 日 MA
        List<MarketIndexDailyEntity> only30 = generate("t00", 30, new BigDecimal("19500"));
        when(repo.findLatestBySymbolBefore(eq("t00"), any(LocalDate.class), any(Pageable.class)))
                .thenReturn(only30);

        Optional<BigDecimal> ma = provider.getTaiex60DayMa(LocalDate.of(2026, 4, 28));

        assertThat(ma).isEmpty();
    }

    @Test
    void getTaiex60DayMa_full60Closes_computesAverage() {
        // 60 筆，全部 19500 → MA 應該也是 19500.0000
        List<MarketIndexDailyEntity> sixty = generate("t00", 60, new BigDecimal("19500"));
        when(repo.findLatestBySymbolBefore(eq("t00"), any(LocalDate.class), any(Pageable.class)))
                .thenReturn(sixty);

        Optional<BigDecimal> ma = provider.getTaiex60DayMa(LocalDate.of(2026, 4, 28));

        assertThat(ma).isPresent();
        assertThat(ma.get().compareTo(new BigDecimal("19500"))).isEqualTo(0);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static MarketIndexDailyEntity bar(String symbol, LocalDate date, BigDecimal close) {
        return new MarketIndexDailyEntity(symbol, date, close, close, close, close, 0L);
    }

    private static List<MarketIndexDailyEntity> generate(String symbol, int n, BigDecimal close) {
        List<MarketIndexDailyEntity> list = new ArrayList<>(n);
        LocalDate d = LocalDate.of(2026, 4, 28);
        for (int i = 0; i < n; i++) {
            list.add(bar(symbol, d.minusDays(i), close));
        }
        return list; // desc-ish; test 只測筆數與平均，順序不重要
    }
}
