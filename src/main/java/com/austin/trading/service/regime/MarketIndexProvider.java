package com.austin.trading.service.regime;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Source-of-truth abstraction for historical price series used by
 * {@link RealDowngradeEvaluator}.
 *
 * <p>Production today does not have a dedicated TAIEX / equity OHLC table;
 * the only place historical TAIEX numbers occasionally surface is inside
 * {@code market_snapshot.payload_json}, and even there most rows carry
 * {@code null} for {@code index_value}. Rather than hard-fail and pretend
 * downgrade triggers can never fire, we route the four historical-data
 * lookups through this small interface so that:</p>
 *
 * <ul>
 *   <li>tests can inject deterministic series for each trigger</li>
 *   <li>future implementations (TWSE crawler, broker API, dedicated
 *       {@code taiex_daily} table) can plug in without touching the
 *       downgrade rules themselves</li>
 *   <li>the default {@link NoopMarketIndexProvider} keeps production
 *       fail-safe: every method returns empty/Optional.empty() and the
 *       evaluator simply does not trigger on data it cannot prove</li>
 * </ul>
 *
 * <p><b>Contract:</b> implementations must return data in <b>chronological
 * order</b> (oldest first) and must <i>only</i> return real, observed closes
 * — never fabricate prices to fill gaps. Missing data → empty list /
 * {@code Optional.empty()}.</p>
 */
public interface MarketIndexProvider {

    /**
     * Most recent {@code n} TAIEX daily closes, oldest first, where the last
     * element is {@code asOf}'s close (or the most recent trading day on or
     * before {@code asOf}). Empty list = data unavailable; the caller must
     * treat this as "trigger does not fire" rather than "trigger fires".
     *
     * @param asOf  the latest trading date to include (inclusive)
     * @param n     desired number of closes; implementations may return fewer
     *              if history is short, but must never pad with synthetic prices
     */
    List<BigDecimal> getTaiexCloses(LocalDate asOf, int n);

    /**
     * Most recent {@code n} closes for the given symbol, oldest first.
     * Used for {@code SEMI_WEAK} on {@code 2330} (TSMC). Same contract as
     * {@link #getTaiexCloses(LocalDate, int)}.
     */
    List<BigDecimal> getSymbolCloses(String symbol, LocalDate asOf, int n);

    /**
     * Optional pre-computed 60-day moving average of TAIEX as of {@code asOf}.
     * Implementations that only have raw closes should return
     * {@code Optional.empty()} and let the evaluator compute the MA itself
     * from {@link #getTaiexCloses(LocalDate, int)}.
     */
    default Optional<BigDecimal> getTaiex60DayMa(LocalDate asOf) {
        return Optional.empty();
    }
}
