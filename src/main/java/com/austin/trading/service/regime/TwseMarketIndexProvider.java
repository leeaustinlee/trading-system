package com.austin.trading.service.regime;

import com.austin.trading.entity.MarketIndexDailyEntity;
import com.austin.trading.repository.MarketIndexDailyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * P0.5 production {@link MarketIndexProvider}：從 {@code market_index_daily}
 * 表撈 TAIEX / 個股歷史收盤，餵 {@link RealDowngradeEvaluator} 三個 trigger。
 *
 * <p>{@link Primary} 蓋過 {@link NoopMarketIndexProvider} 的 fallback bean。
 * 此 provider 不直接打 TWSE API（那是 {@link com.austin.trading.scheduler.MarketIndexDataPrepJob}
 * 與 {@link MarketIndexBackfillService} 的責任），只讀 DB；DB 缺資料 → 回空 list，
 * trigger 自動 fail-safe 不觸發。</p>
 *
 * <p>所有方法返回 oldest-first List，符合 {@link MarketIndexProvider} 介面契約。</p>
 */
@Component
@Primary
public class TwseMarketIndexProvider implements MarketIndexProvider {

    private static final Logger log = LoggerFactory.getLogger(TwseMarketIndexProvider.class);
    private static final String TAIEX_SYMBOL = "t00";

    private final MarketIndexDailyRepository repository;

    public TwseMarketIndexProvider(MarketIndexDailyRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<BigDecimal> getTaiexCloses(LocalDate asOf, int n) {
        return getSymbolCloses(TAIEX_SYMBOL, asOf, n);
    }

    @Override
    public List<BigDecimal> getSymbolCloses(String symbol, LocalDate asOf, int n) {
        if (symbol == null || symbol.isBlank() || n <= 0 || asOf == null) return List.of();
        try {
            List<MarketIndexDailyEntity> latest = repository.findLatestBySymbolBefore(
                    symbol, asOf, PageRequest.of(0, n));
            if (latest == null || latest.isEmpty()) {
                log.debug("[TwseMarketIndexProvider] {} closes asOf={} n={} → empty (DB 無資料)",
                        symbol, asOf, n);
                return List.of();
            }
            // findLatestBySymbolBefore 回傳 desc，反轉成 oldest-first 符合介面契約。
            List<BigDecimal> closes = new ArrayList<>(latest.size());
            for (int i = latest.size() - 1; i >= 0; i--) {
                BigDecimal c = latest.get(i).getClosePrice();
                if (c != null) closes.add(c);
            }
            return Collections.unmodifiableList(closes);
        } catch (Exception e) {
            log.warn("[TwseMarketIndexProvider] getSymbolCloses {} asOf={} 失敗: {}",
                    symbol, asOf, e.getMessage());
            return List.of();
        }
    }

    /**
     * 直接從 DB 算 60 日 TAIEX 平均；不夠 60 筆回 {@code Optional.empty()}，
     * 由 evaluator 自行決定不觸發。
     */
    @Override
    public Optional<BigDecimal> getTaiex60DayMa(LocalDate asOf) {
        List<BigDecimal> closes = getTaiexCloses(asOf, 60);
        if (closes.size() < 60) return Optional.empty();
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal c : closes) {
            if (c == null) return Optional.empty();
            sum = sum.add(c);
        }
        return Optional.of(sum.divide(BigDecimal.valueOf(closes.size()), 4, RoundingMode.HALF_UP));
    }
}
