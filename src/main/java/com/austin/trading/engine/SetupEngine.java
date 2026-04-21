package com.austin.trading.engine;

import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.SetupDecision;
import com.austin.trading.dto.internal.SetupEvaluationInput;
import com.austin.trading.dto.internal.ThemeStrengthDecision;
import com.austin.trading.service.ScoreConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateless setup classifier for three setup types:
 * <ul>
 *   <li>{@code BREAKOUT_CONTINUATION}  — price near/above base high, volume confirming</li>
 *   <li>{@code PULLBACK_CONFIRMATION}  — price at MA support, structure intact</li>
 *   <li>{@code EVENT_SECOND_LEG}       — event catalyst, post-consolidation re-acceleration</li>
 * </ul>
 *
 * <p>Evaluation order: BREAKOUT → PULLBACK → EVENT_SECOND_LEG. First valid setup
 * wins. If none pass, the result is {@code valid=false} with the last rejection
 * reason for audit.</p>
 *
 * <p>This engine is <b>pure</b>: no I/O, no DB, no clock dependency.</p>
 */
@Component
public class SetupEngine {

    private static final Logger log = LoggerFactory.getLogger(SetupEngine.class);

    public static final String SETUP_BREAKOUT = "BREAKOUT_CONTINUATION";
    public static final String SETUP_PULLBACK = "PULLBACK_CONFIRMATION";
    public static final String SETUP_EVENT    = "EVENT_SECOND_LEG";

    private static final String TRAILING_MA5  = "MA5_TRAIL";
    private static final String TRAILING_MA10 = "MA10_TRAIL";
    private static final BigDecimal HUNDRED   = new BigDecimal("100");

    private final ScoreConfigService scoreConfig;
    private final ObjectMapper objectMapper;

    public SetupEngine(ScoreConfigService scoreConfig, ObjectMapper objectMapper) {
        this.scoreConfig  = scoreConfig;
        this.objectMapper = objectMapper;
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Evaluate a list of inputs; returns one {@link SetupDecision} per input. */
    public List<SetupDecision> evaluate(List<SetupEvaluationInput> inputs) {
        if (inputs == null) return List.of();
        List<SetupDecision> out = new ArrayList<>(inputs.size());
        for (SetupEvaluationInput in : inputs) out.add(evaluateOne(in));
        return out;
    }

    /**
     * Evaluate a single input. Tries each regime-allowed setup type in order;
     * returns the first valid one, or an invalid decision if none pass.
     */
    public SetupDecision evaluateOne(SetupEvaluationInput in) {
        if (in == null) return null;

        if (in.regime() != null && !in.regime().tradeAllowed()) {
            return invalid(in, null,
                    "REGIME_TRADE_NOT_ALLOWED(" + in.regime().regimeType() + ")",
                    buildPayload(in, null, "regime_trade_blocked"));
        }

        List<String> allowed = allowedSetupTypes(in.regime());

        if (allowed.isEmpty()) {
            return invalid(in, null, "REGIME_BLOCKS_ALL_SETUPS", buildPayload(in, null, "regime_blocked"));
        }

        ThemeStrengthDecision theme = in.themeDecision();
        if (theme != null && !theme.tradable()) {
            return invalid(in, null, "THEME_NOT_TRADABLE(decay=" + theme.decayRisk() + ")",
                    buildPayload(in, null, "theme_not_tradable"));
        }

        if (in.currentPrice() == null) {
            return invalid(in, null, "MISSING_CURRENT_PRICE", buildPayload(in, null, "no_price"));
        }

        List<String> rejections = new ArrayList<>();
        for (String type : List.of(SETUP_BREAKOUT, SETUP_PULLBACK, SETUP_EVENT)) {
            if (!allowed.contains(type)) continue;
            SetupDecision d = trySetup(in, type);
            if (d.valid()) return d;
            if (d.rejectionReason() != null) rejections.add(d.rejectionReason());
        }

        String combinedReason = rejections.isEmpty() ? "NO_VALID_SETUP" : String.join(" | ", rejections);
        return invalid(in, null, combinedReason, buildPayload(in, null, "no_valid_setup"));
    }

    // ── Per-setup-type evaluators ──────────────────────────────────────────

    private SetupDecision trySetup(SetupEvaluationInput in, String type) {
        return switch (type) {
            case SETUP_BREAKOUT -> evalBreakout(in);
            case SETUP_PULLBACK -> evalPullback(in);
            case SETUP_EVENT    -> evalEvent(in);
            default             -> invalid(in, type, "UNKNOWN_SETUP_TYPE", "{}");
        };
    }

    private SetupDecision evalBreakout(SetupEvaluationInput in) {
        BigDecimal volMult = scoreConfig.getDecimal("setup.breakout.volume_multiplier", new BigDecimal("1.50"));
        BigDecimal nearPct = scoreConfig.getDecimal("setup.breakout.near_base_pct",     new BigDecimal("2.00"));

        BigDecimal baseHigh = in.baseHigh();
        if (baseHigh == null || baseHigh.compareTo(BigDecimal.ZERO) <= 0) {
            return invalid(in, SETUP_BREAKOUT, "BREAKOUT:MISSING_BASE_HIGH",
                    buildPayload(in, SETUP_BREAKOUT, "no_base_high"));
        }

        // Price within nearPct% below baseHigh, or above
        BigDecimal lowerBound = baseHigh.multiply(
                BigDecimal.ONE.subtract(nearPct.divide(HUNDRED, 4, RoundingMode.HALF_UP)));
        if (in.currentPrice().compareTo(lowerBound) < 0) {
            return invalid(in, SETUP_BREAKOUT,
                    "BREAKOUT:PRICE_TOO_FAR_BELOW_BASE_HIGH(price=" + in.currentPrice()
                            + " need>=" + lowerBound.setScale(2, RoundingMode.HALF_UP) + ")",
                    buildPayload(in, SETUP_BREAKOUT, "price_too_far"));
        }

        if (!volumeOk(in, volMult)) {
            return invalid(in, SETUP_BREAKOUT,
                    "BREAKOUT:INSUFFICIENT_VOLUME(need=" + volMult + "x avg)",
                    buildPayload(in, SETUP_BREAKOUT, "low_volume"));
        }

        BigDecimal stopPct = scoreConfig.getDecimal("setup.stop.breakout_pct", new BigDecimal("4.0"));
        BigDecimal tp1Pct  = scoreConfig.getDecimal("setup.tp1.default_pct",   new BigDecimal("7.0"));
        BigDecimal tp2Pct  = scoreConfig.getDecimal("setup.tp2.default_pct",   new BigDecimal("13.0"));
        int holdDays       = scoreConfig.getInt("setup.breakout.holding_days", 10);

        BigDecimal ideal    = baseHigh;
        BigDecimal zoneLow  = baseHigh;
        BigDecimal zoneHigh = baseHigh.multiply(new BigDecimal("1.02")).setScale(4, RoundingMode.HALF_UP);
        BigDecimal invalidAt = in.baseLow() != null ? in.baseLow()
                : baseHigh.multiply(new BigDecimal("0.95")).setScale(4, RoundingMode.HALF_UP);

        return valid(in, SETUP_BREAKOUT, zoneLow, zoneHigh, ideal, invalidAt,
                stopPrice(ideal, stopPct), tp1(ideal, tp1Pct), tp2(ideal, tp2Pct),
                TRAILING_MA5, holdDays, buildPayload(in, SETUP_BREAKOUT, "pass"));
    }

    private SetupDecision evalPullback(SetupEvaluationInput in) {
        BigDecimal maxDepthPct = scoreConfig.getDecimal("setup.pullback.max_depth_pct", new BigDecimal("8.0"));

        BigDecimal ma5  = in.ma5();
        BigDecimal ma10 = in.ma10();
        if (ma5 == null && ma10 == null) {
            return invalid(in, SETUP_PULLBACK, "PULLBACK:MISSING_MA_DATA",
                    buildPayload(in, SETUP_PULLBACK, "no_ma"));
        }

        BigDecimal support = ma5  != null ? ma5  : ma10;
        BigDecimal anchor  = ma10 != null ? ma10 : ma5;

        if (in.currentPrice().compareTo(anchor) < 0) {
            return invalid(in, SETUP_PULLBACK,
                    "PULLBACK:BELOW_MA10(price=" + in.currentPrice() + " ma10=" + anchor + ")",
                    buildPayload(in, SETUP_PULLBACK, "below_ma10"));
        }

        if (in.recentSwingHigh() != null) {
            BigDecimal depthPct = in.recentSwingHigh().subtract(in.currentPrice())
                    .divide(in.recentSwingHigh(), 4, RoundingMode.HALF_UP)
                    .multiply(HUNDRED);
            if (depthPct.compareTo(maxDepthPct) > 0) {
                return invalid(in, SETUP_PULLBACK,
                        "PULLBACK:TOO_DEEP(" + depthPct.setScale(1, RoundingMode.HALF_UP) + "% > " + maxDepthPct + "%)",
                        buildPayload(in, SETUP_PULLBACK, "too_deep"));
            }
        }

        BigDecimal stopPct = scoreConfig.getDecimal("setup.stop.pullback_pct", new BigDecimal("5.0"));
        BigDecimal tp1Pct  = scoreConfig.getDecimal("setup.tp1.default_pct",   new BigDecimal("7.0"));
        BigDecimal tp2Pct  = scoreConfig.getDecimal("setup.tp2.default_pct",   new BigDecimal("13.0"));
        int holdDays       = scoreConfig.getInt("setup.pullback.holding_days", 8);

        BigDecimal ideal    = support;
        BigDecimal zoneLow  = anchor.min(support);
        BigDecimal zoneHigh = anchor.max(support);
        BigDecimal invalidAt = anchor.multiply(new BigDecimal("0.99")).setScale(4, RoundingMode.HALF_UP);

        return valid(in, SETUP_PULLBACK, zoneLow, zoneHigh, ideal, invalidAt,
                stopPrice(ideal, stopPct), tp1(ideal, tp1Pct), tp2(ideal, tp2Pct),
                TRAILING_MA10, holdDays, buildPayload(in, SETUP_PULLBACK, "pass"));
    }

    private SetupDecision evalEvent(SetupEvaluationInput in) {
        if (!in.eventDriven()) {
            return invalid(in, SETUP_EVENT, "EVENT:NOT_EVENT_DRIVEN",
                    buildPayload(in, SETUP_EVENT, "no_event"));
        }

        int minDays = scoreConfig.getInt("setup.event.min_consolidation_days", 3);
        int maxDays = scoreConfig.getInt("setup.event.max_consolidation_days", 10);

        if (in.consolidationDays() < minDays || in.consolidationDays() > maxDays) {
            return invalid(in, SETUP_EVENT,
                    "EVENT:CONSOLIDATION_OUT_OF_RANGE(days=" + in.consolidationDays()
                            + " range=[" + minDays + "," + maxDays + "])",
                    buildPayload(in, SETUP_EVENT, "bad_consolidation_days"));
        }

        if (in.baseLow() != null && in.currentPrice().compareTo(in.baseLow()) < 0) {
            return invalid(in, SETUP_EVENT,
                    "EVENT:RANGE_BROKEN_DOWN(price=" + in.currentPrice() + " baseLow=" + in.baseLow() + ")",
                    buildPayload(in, SETUP_EVENT, "range_broken"));
        }

        BigDecimal stopPct = scoreConfig.getDecimal("setup.stop.event_pct",  new BigDecimal("4.0"));
        BigDecimal tp1Pct  = scoreConfig.getDecimal("setup.tp1.default_pct", new BigDecimal("7.0"));
        BigDecimal tp2Pct  = scoreConfig.getDecimal("setup.tp2.default_pct", new BigDecimal("13.0"));
        int holdDays       = scoreConfig.getInt("setup.event.holding_days", 7);

        BigDecimal ideal    = in.currentPrice();
        BigDecimal zoneLow  = in.baseLow()  != null ? in.baseLow()
                : ideal.multiply(new BigDecimal("0.98")).setScale(4, RoundingMode.HALF_UP);
        BigDecimal zoneHigh = in.baseHigh() != null ? in.baseHigh()
                : ideal.multiply(new BigDecimal("1.02")).setScale(4, RoundingMode.HALF_UP);
        BigDecimal invalidAt = zoneLow.multiply(new BigDecimal("0.99")).setScale(4, RoundingMode.HALF_UP);

        return valid(in, SETUP_EVENT, zoneLow, zoneHigh, ideal, invalidAt,
                stopPrice(ideal, stopPct), tp1(ideal, tp1Pct), tp2(ideal, tp2Pct),
                TRAILING_MA5, holdDays, buildPayload(in, SETUP_EVENT, "pass"));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private List<String> allowedSetupTypes(MarketRegimeDecision regime) {
        if (regime == null || regime.allowedSetupTypes() == null) {
            // Conservative fallback when regime is unknown: RANGE_CHOP defaults
            return List.of(SETUP_PULLBACK, SETUP_EVENT);
        }
        return regime.allowedSetupTypes();
    }

    private boolean volumeOk(SetupEvaluationInput in, BigDecimal multiplier) {
        if (in.currentVolume() == null || in.avgVolume5() == null
                || in.avgVolume5().compareTo(BigDecimal.ZERO) == 0) {
            return true; // skip check when volume data absent
        }
        return in.currentVolume().compareTo(in.avgVolume5().multiply(multiplier)) >= 0;
    }

    private BigDecimal stopPrice(BigDecimal entry, BigDecimal pct) {
        return entry.multiply(BigDecimal.ONE.subtract(pct.divide(HUNDRED, 6, RoundingMode.HALF_UP)))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal tp1(BigDecimal entry, BigDecimal pct) {
        return entry.multiply(BigDecimal.ONE.add(pct.divide(HUNDRED, 6, RoundingMode.HALF_UP)))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal tp2(BigDecimal entry, BigDecimal pct) {
        return entry.multiply(BigDecimal.ONE.add(pct.divide(HUNDRED, 6, RoundingMode.HALF_UP)))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private SetupDecision valid(SetupEvaluationInput in, String type,
                                BigDecimal zoneLow, BigDecimal zoneHigh,
                                BigDecimal ideal, BigDecimal invalidAt,
                                BigDecimal stop, BigDecimal tp1, BigDecimal tp2,
                                String trailing, int holdDays, String payload) {
        LocalDate date = in.candidate() != null ? in.candidate().tradingDate() : null;
        String sym     = in.candidate() != null ? in.candidate().symbol() : "UNKNOWN";
        return new SetupDecision(date, sym, type, true,
                zoneLow, zoneHigh, ideal, invalidAt, stop, tp1, tp2,
                trailing, holdDays, null, payload);
    }

    private SetupDecision invalid(SetupEvaluationInput in, String type,
                                  String reason, String payload) {
        LocalDate date = in.candidate() != null ? in.candidate().tradingDate() : null;
        String sym     = in.candidate() != null ? in.candidate().symbol() : "UNKNOWN";
        return new SetupDecision(date, sym, type, false,
                null, null, null, null, null, null, null,
                null, null, reason, payload);
    }

    private String buildPayload(SetupEvaluationInput in, String setupType, String outcome) {
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("setupType",         setupType);
            n.put("outcome",           outcome);
            if (in.currentPrice()    != null) n.put("currentPrice",    in.currentPrice());
            if (in.baseHigh()        != null) n.put("baseHigh",        in.baseHigh());
            if (in.baseLow()         != null) n.put("baseLow",         in.baseLow());
            if (in.ma5()             != null) n.put("ma5",             in.ma5());
            if (in.ma10()            != null) n.put("ma10",            in.ma10());
            if (in.recentSwingHigh() != null) n.put("recentSwingHigh", in.recentSwingHigh());
            if (in.avgVolume5()      != null) n.put("avgVolume5",      in.avgVolume5());
            if (in.currentVolume()   != null) n.put("currentVolume",   in.currentVolume());
            n.put("consolidationDays", in.consolidationDays());
            n.put("eventDriven",       in.eventDriven());
            return objectMapper.writeValueAsString(n);
        } catch (Exception e) {
            log.warn("[SetupEngine] payload serialization failed");
            return "{}";
        }
    }
}
