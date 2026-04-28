package com.austin.trading.service.regime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Production fallback {@link MarketIndexProvider}: returns empty results
 * for every query. Plug-in real implementations (TWSE crawler, broker API,
 * dedicated {@code taiex_daily} table, …) by registering another bean
 * implementing {@link MarketIndexProvider}; this configuration backs off
 * via {@link ConditionalOnMissingBean}.
 *
 * <p>Why not throw / return synthetic prices? The contract for
 * {@link RealDowngradeEvaluator} is <i>fail-safe</i>: if we cannot prove a
 * trigger fired, we do <b>not</b> downgrade. Returning empty makes that
 * fall-through automatic and keeps the regime engine producing A/B grades
 * for days where historical TAIEX data is genuinely absent — exactly the
 * behaviour we had before P0.2 went live.</p>
 */
@Configuration
public class NoopMarketIndexProvider {

    private static final Logger log = LoggerFactory.getLogger(NoopMarketIndexProvider.class);

    @Bean(name = "fallbackMarketIndexProvider")
    @ConditionalOnMissingBean(MarketIndexProvider.class)
    public MarketIndexProvider fallbackMarketIndexProvider() {
        log.info("[MarketIndexProvider] no production implementation registered — "
                + "using NOOP fallback (returns empty for all queries; downgrade triggers "
                + "depending on TAIEX/equity history will not fire — fail-safe).");
        return new MarketIndexProvider() {
            @Override
            public List<BigDecimal> getTaiexCloses(LocalDate asOf, int n) {
                return List.of();
            }

            @Override
            public List<BigDecimal> getSymbolCloses(String symbol, LocalDate asOf, int n) {
                return List.of();
            }

            @Override
            public Optional<BigDecimal> getTaiex60DayMa(LocalDate asOf) {
                return Optional.empty();
            }
        };
    }
}
