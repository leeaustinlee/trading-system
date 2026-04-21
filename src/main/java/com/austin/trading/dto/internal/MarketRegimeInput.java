package com.austin.trading.dto.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Input contract for {@link com.austin.trading.engine.MarketRegimeEngine}.
 *
 * <p>Built by {@code MarketRegimeService} from {@code market_snapshot} +
 * optional parsed fields out of {@code market_snapshot.payload_json}. When a
 * field cannot be derived from upstream data, service layer must supply a
 * conservative default and record the fact in the stored
 * {@code input_snapshot_json} so that later debug sessions can tell fallback
 * values apart from real ones.</p>
 */
public record MarketRegimeInput(
        LocalDate tradingDate,
        LocalDateTime evaluatedAt,
        /** Legacy A/B/C grade if available (kept as a hint, not the primary router). */
        String marketGrade,
        String marketPhase,
        /** TSMC short-term trend strength in [-1, 1]. 0 = neutral. */
        BigDecimal tsmcTrendScore,
        /** Ratio of advancers vs total listed stocks in [0, 1]. */
        BigDecimal breadthPositiveRatio,
        /** Ratio of decliners vs total listed stocks in [0, 1]. */
        BigDecimal breadthNegativeRatio,
        /** Ratio of current leaders that are still strong in [0, 1]. */
        BigDecimal leadersStrongRatio,
        /** TAIEX distance from its 10-day MA, as a percentage (positive = above MA). */
        BigDecimal indexDistanceFromMa10Pct,
        /** TAIEX distance from its 20-day MA, as a percentage (positive = above MA). */
        BigDecimal indexDistanceFromMa20Pct,
        /** Intraday realized volatility percentage (e.g. high/low range of TAIEX). */
        BigDecimal intradayVolatilityPct,
        boolean washoutRebound,
        boolean nearHighNotBreak,
        boolean blowoffSignal
) {

    /**
     * Builder for tests + service layer. Canonical 14-field ctor is noisy;
     * use {@code new Builder()...build()} to keep call-sites readable.
     */
    public static final class Builder {
        private LocalDate tradingDate;
        private LocalDateTime evaluatedAt;
        private String marketGrade;
        private String marketPhase;
        private BigDecimal tsmcTrendScore;
        private BigDecimal breadthPositiveRatio;
        private BigDecimal breadthNegativeRatio;
        private BigDecimal leadersStrongRatio;
        private BigDecimal indexDistanceFromMa10Pct;
        private BigDecimal indexDistanceFromMa20Pct;
        private BigDecimal intradayVolatilityPct;
        private boolean washoutRebound;
        private boolean nearHighNotBreak;
        private boolean blowoffSignal;

        public Builder tradingDate(LocalDate v)              { this.tradingDate = v; return this; }
        public Builder evaluatedAt(LocalDateTime v)          { this.evaluatedAt = v; return this; }
        public Builder marketGrade(String v)                 { this.marketGrade = v; return this; }
        public Builder marketPhase(String v)                 { this.marketPhase = v; return this; }
        public Builder tsmcTrendScore(BigDecimal v)          { this.tsmcTrendScore = v; return this; }
        public Builder breadthPositiveRatio(BigDecimal v)    { this.breadthPositiveRatio = v; return this; }
        public Builder breadthNegativeRatio(BigDecimal v)    { this.breadthNegativeRatio = v; return this; }
        public Builder leadersStrongRatio(BigDecimal v)      { this.leadersStrongRatio = v; return this; }
        public Builder indexDistanceFromMa10Pct(BigDecimal v){ this.indexDistanceFromMa10Pct = v; return this; }
        public Builder indexDistanceFromMa20Pct(BigDecimal v){ this.indexDistanceFromMa20Pct = v; return this; }
        public Builder intradayVolatilityPct(BigDecimal v)   { this.intradayVolatilityPct = v; return this; }
        public Builder washoutRebound(boolean v)             { this.washoutRebound = v; return this; }
        public Builder nearHighNotBreak(boolean v)           { this.nearHighNotBreak = v; return this; }
        public Builder blowoffSignal(boolean v)              { this.blowoffSignal = v; return this; }

        public MarketRegimeInput build() {
            return new MarketRegimeInput(
                    tradingDate, evaluatedAt, marketGrade, marketPhase,
                    tsmcTrendScore, breadthPositiveRatio, breadthNegativeRatio,
                    leadersStrongRatio, indexDistanceFromMa10Pct,
                    indexDistanceFromMa20Pct, intradayVolatilityPct,
                    washoutRebound, nearHighNotBreak, blowoffSignal);
        }
    }
}
