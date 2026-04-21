package com.austin.trading.engine;

import com.austin.trading.dto.internal.BenchmarkInput;
import com.austin.trading.dto.internal.BenchmarkReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * P2.2 Benchmark Analytics Engine.
 *
 * <p>Compares strategy performance against:
 * <ul>
 *   <li>Market benchmark — mean {@code avg_gain_pct} across all active themes</li>
 *   <li>Theme benchmark  — mean {@code avg_gain_pct} restricted to themes traded</li>
 * </ul>
 * Alpha threshold: ±0.5% is MATCH; outside that is OUTPERFORM or UNDERPERFORM.</p>
 */
@Component
public class BenchmarkAnalyticsEngine {

    static final String OUTPERFORM  = "OUTPERFORM";
    static final String MATCH       = "MATCH";
    static final String UNDERPERFORM = "UNDERPERFORM";
    static final String UNKNOWN     = "UNKNOWN";

    private static final BigDecimal MATCH_BAND = new BigDecimal("0.5");

    private final ObjectMapper objectMapper;

    public BenchmarkAnalyticsEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public BenchmarkReport evaluate(BenchmarkInput in) {
        if (in == null) return null;
        if (in.strategyAvgReturn() == null || in.strategyTradeCount() == 0) {
            return new BenchmarkReport(
                    in.startDate(), in.endDate(),
                    in.strategyAvgReturn(), in.marketAvgGain(), in.tradedThemeAvgGain(),
                    null, null, UNKNOWN, UNKNOWN, in.strategyTradeCount(), null);
        }

        BigDecimal mktAlpha = computeAlpha(in.strategyAvgReturn(), in.marketAvgGain());
        BigDecimal thmAlpha = computeAlpha(in.strategyAvgReturn(), in.tradedThemeAvgGain());

        String mktVerdict = verdict(mktAlpha);
        String thmVerdict = verdict(thmAlpha);

        String payload = buildPayload(in, mktAlpha, thmAlpha, mktVerdict, thmVerdict);
        return new BenchmarkReport(
                in.startDate(), in.endDate(),
                in.strategyAvgReturn(), in.marketAvgGain(), in.tradedThemeAvgGain(),
                mktAlpha, thmAlpha,
                mktVerdict, thmVerdict,
                in.strategyTradeCount(), payload);
    }

    // ── package-private helpers for tests ─────────────────────────────────────

    BigDecimal computeAlpha(BigDecimal strategyReturn, BigDecimal benchmark) {
        if (strategyReturn == null || benchmark == null) return null;
        return strategyReturn.subtract(benchmark).setScale(4, RoundingMode.HALF_UP);
    }

    String verdict(BigDecimal alpha) {
        if (alpha == null) return UNKNOWN;
        if (alpha.compareTo(MATCH_BAND) > 0)           return OUTPERFORM;
        if (alpha.compareTo(MATCH_BAND.negate()) < 0)  return UNDERPERFORM;
        return MATCH;
    }

    // ── private ───────────────────────────────────────────────────────────────

    private String buildPayload(BenchmarkInput in, BigDecimal mktAlpha, BigDecimal thmAlpha,
                                 String mktVerdict, String thmVerdict) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "strategyAvgReturn", str(in.strategyAvgReturn()),
                    "marketAvgGain",     str(in.marketAvgGain()),
                    "tradedThemeAvgGain",str(in.tradedThemeAvgGain()),
                    "marketAlpha",       str(mktAlpha),
                    "themeAlpha",        str(thmAlpha),
                    "marketVerdict",     mktVerdict,
                    "themeVerdict",      thmVerdict,
                    "tradeCount",        in.strategyTradeCount(),
                    "period",            in.startDate() + "/" + in.endDate()
            ));
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(BigDecimal v) {
        return v == null ? "N/A" : v.toPlainString();
    }
}
