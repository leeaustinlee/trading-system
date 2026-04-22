package com.austin.trading.engine;

import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.MarketRegimeInput;
import com.austin.trading.service.ScoreConfigService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateless classifier that maps a {@link MarketRegimeInput} to one of four
 * regimes:
 *
 * <ul>
 *   <li>{@code BULL_TREND}        — breadth and leadership aligned, index above MAs</li>
 *   <li>{@code RANGE_CHOP}        — neither clearly up nor down, MA distance small</li>
 *   <li>{@code WEAK_DOWNTREND}    — index below 20MA, negative breadth elevated, leaders weak</li>
 *   <li>{@code PANIC_VOLATILITY}  — high intraday volatility or negative breadth extreme</li>
 * </ul>
 *
 * <p>Engine is <b>pure</b>: no I/O, no DB, no clock dependency beyond
 * {@code input.evaluatedAt()}. Service layer is responsible for shaping the
 * input (and marking fallback fields).</p>
 */
@Component
public class MarketRegimeEngine {

    private static final Logger log = LoggerFactory.getLogger(MarketRegimeEngine.class);

    public static final String REGIME_BULL_TREND       = "BULL_TREND";
    public static final String REGIME_RANGE_CHOP       = "RANGE_CHOP";
    public static final String REGIME_WEAK_DOWNTREND   = "WEAK_DOWNTREND";
    public static final String REGIME_PANIC_VOLATILITY = "PANIC_VOLATILITY";

    public static final String SETUP_BREAKOUT   = "BREAKOUT_CONTINUATION";
    public static final String SETUP_PULLBACK   = "PULLBACK_CONFIRMATION";
    public static final String SETUP_EVENT_2ND  = "EVENT_SECOND_LEG";

    private final ScoreConfigService scoreConfig;
    private final ObjectMapper objectMapper;

    public MarketRegimeEngine(ScoreConfigService scoreConfig, ObjectMapper objectMapper) {
        this.scoreConfig  = scoreConfig;
        this.objectMapper = objectMapper;
    }

    /**
     * Classify the current market state.
     *
     * <p>Rule order (first match wins):</p>
     * <ol>
     *   <li>PANIC_VOLATILITY  — volatility or negative breadth cross config thresholds</li>
     *   <li>WEAK_DOWNTREND    — index below 20MA + weak leaders + elevated negative breadth</li>
     *   <li>BULL_TREND        — grade A / strong breadth / strong leaders / index above both MAs</li>
     *   <li>RANGE_CHOP        — fallback for anything in between</li>
     * </ol>
     */
    public MarketRegimeDecision evaluate(MarketRegimeInput input) {
        List<String> reasons = new ArrayList<>();

        // v2.6 MVP：追蹤 null input 以產出 confidence level + missingSignals
        List<String> missingSignals = new ArrayList<>();
        if (input.breadthPositiveRatio()     == null) missingSignals.add("breadthPositive");
        if (input.breadthNegativeRatio()     == null) missingSignals.add("breadthNegative");
        if (input.leadersStrongRatio()       == null) missingSignals.add("leadersStrong");
        if (input.indexDistanceFromMa10Pct() == null) missingSignals.add("ma10Dist");
        if (input.indexDistanceFromMa20Pct() == null) missingSignals.add("ma20Dist");
        if (input.intradayVolatilityPct()    == null) missingSignals.add("volatility");

        BigDecimal breadthPos  = safe(input.breadthPositiveRatio(), new BigDecimal("0.50"));
        BigDecimal breadthNeg  = safe(input.breadthNegativeRatio(), new BigDecimal("0.50"));
        BigDecimal leaders     = safe(input.leadersStrongRatio(),   new BigDecimal("0.50"));
        BigDecimal ma10Dist    = safe(input.indexDistanceFromMa10Pct(), BigDecimal.ZERO);
        BigDecimal ma20Dist    = safe(input.indexDistanceFromMa20Pct(), BigDecimal.ZERO);
        BigDecimal volatility  = safe(input.intradayVolatilityPct(),    BigDecimal.ZERO);
        String grade = input.marketGrade() == null ? "" : input.marketGrade().trim().toUpperCase();

        // --- thresholds (all read from score_config with conservative defaults) ---
        BigDecimal bullMinBreadth  = scoreConfig.getDecimal("regime.bull.min_breadth_ratio",            new BigDecimal("0.55"));
        BigDecimal bullMinLeaders  = scoreConfig.getDecimal("regime.bull.min_leaders_ratio",            new BigDecimal("0.55"));
        BigDecimal rangeMaxMaDist  = scoreConfig.getDecimal("regime.range.max_ma_distance_pct",         new BigDecimal("1.50"));
        BigDecimal weakNegBreadth  = scoreConfig.getDecimal("regime.weakdown.min_negative_breadth_ratio", new BigDecimal("0.55"));
        BigDecimal panicVol        = scoreConfig.getDecimal("regime.panic.volatility_threshold",        new BigDecimal("2.50"));
        BigDecimal panicNegBreadth = scoreConfig.getDecimal("regime.panic.min_negative_breadth_ratio",  new BigDecimal("0.75"));

        // --- classify ---
        String regimeType;

        boolean panicByVol     = input.intradayVolatilityPct()    != null && volatility.compareTo(panicVol)        >= 0;
        boolean panicByBreadth = input.breadthNegativeRatio()     != null && breadthNeg.compareTo(panicNegBreadth) >= 0;

        if (input.blowoffSignal() || panicByVol || panicByBreadth) {
            regimeType = REGIME_PANIC_VOLATILITY;
            if (input.blowoffSignal()) reasons.add("blowoff_signal=true");
            if (panicByVol)     reasons.add("intraday_volatility_pct=" + volatility + ">=" + panicVol);
            if (panicByBreadth) reasons.add("breadth_negative_ratio=" + breadthNeg + ">=" + panicNegBreadth);
        } else if (ma20Dist.signum() < 0
                && breadthNeg.compareTo(weakNegBreadth) >= 0
                && leaders.compareTo(new BigDecimal("0.40")) < 0) {
            regimeType = REGIME_WEAK_DOWNTREND;
            reasons.add("index_below_ma20 (" + ma20Dist + "%)");
            reasons.add("breadth_negative_ratio=" + breadthNeg + ">=" + weakNegBreadth);
            reasons.add("leaders_strong_ratio=" + leaders + "<0.40");
        } else if ("A".equals(grade)
                && breadthPos.compareTo(bullMinBreadth) >= 0
                && leaders.compareTo(bullMinLeaders)   >= 0
                && ma10Dist.signum() >= 0
                && ma20Dist.signum() >= 0) {
            regimeType = REGIME_BULL_TREND;
            reasons.add("grade=A");
            reasons.add("breadth_positive_ratio=" + breadthPos + ">=" + bullMinBreadth);
            reasons.add("leaders_strong_ratio=" + leaders + ">=" + bullMinLeaders);
            reasons.add("index>=ma10 (" + ma10Dist + "%) and index>=ma20 (" + ma20Dist + "%)");
        } else {
            regimeType = REGIME_RANGE_CHOP;
            reasons.add("no clear bull/weak/panic signal");
            if (ma10Dist.abs().compareTo(rangeMaxMaDist) <= 0) {
                reasons.add("|index - ma10|%=" + ma10Dist + "<=" + rangeMaxMaDist);
            }
        }

        BigDecimal riskMultiplier = resolveRiskMultiplier(regimeType);
        boolean tradeAllowed = !REGIME_PANIC_VOLATILITY.equals(regimeType);
        List<String> allowedSetups = resolveAllowedSetups(regimeType);

        // v2.6 MVP：計算 confidence level
        String confidenceLevel = computeConfidenceLevel(missingSignals.size());
        if (!missingSignals.isEmpty()) {
            reasons.add("missing_signals=" + String.join(",", missingSignals)
                    + " confidence=" + confidenceLevel);
        }

        String summary = String.format(
                "regime=%s trade_allowed=%b risk_mult=%s grade=%s conf=%s miss=%d breadth_pos=%s leaders=%s ma10=%s ma20=%s vol=%s",
                regimeType, tradeAllowed, riskMultiplier,
                grade.isEmpty() ? "?" : grade,
                confidenceLevel, missingSignals.size(),
                breadthPos, leaders, ma10Dist, ma20Dist, volatility);

        String reasonsJson = serializeReasons(reasons);
        String inputSnapshotJson = serializeInput(input);

        LocalDateTime evaluatedAt = input.evaluatedAt() == null ? LocalDateTime.now() : input.evaluatedAt();
        return new MarketRegimeDecision(
                input.tradingDate(),
                evaluatedAt,
                regimeType,
                grade.isEmpty() ? null : grade,
                tradeAllowed,
                riskMultiplier,
                allowedSetups,
                summary,
                reasonsJson,
                inputSnapshotJson,
                confidenceLevel,
                List.copyOf(missingSignals)
        );
    }

    /**
     * v2.6 MVP: 依缺資料數量計算 confidence。
     * <ul>
     *   <li>0 個缺 → HIGH</li>
     *   <li>1~2 個缺 → MEDIUM</li>
     *   <li>3+ 個缺 → LOW</li>
     * </ul>
     */
    private String computeConfidenceLevel(int missingCount) {
        if (missingCount == 0) return MarketRegimeDecision.CONFIDENCE_HIGH;
        if (missingCount <= 2) return MarketRegimeDecision.CONFIDENCE_MEDIUM;
        return MarketRegimeDecision.CONFIDENCE_LOW;
    }

    public boolean isTradeAllowed(MarketRegimeDecision decision) {
        return decision != null && decision.tradeAllowed();
    }

    public BigDecimal resolveRiskMultiplier(String regimeType) {
        String key = switch (regimeType == null ? "" : regimeType) {
            case REGIME_BULL_TREND       -> "regime.risk_multiplier.bull";
            case REGIME_RANGE_CHOP       -> "regime.risk_multiplier.range";
            case REGIME_WEAK_DOWNTREND   -> "regime.risk_multiplier.weakdown";
            case REGIME_PANIC_VOLATILITY -> "regime.risk_multiplier.panic";
            default                      -> "regime.risk_multiplier.range";
        };
        BigDecimal def = switch (regimeType == null ? "" : regimeType) {
            case REGIME_BULL_TREND       -> new BigDecimal("1.00");
            case REGIME_RANGE_CHOP       -> new BigDecimal("0.50");
            case REGIME_WEAK_DOWNTREND   -> new BigDecimal("0.25");
            case REGIME_PANIC_VOLATILITY -> BigDecimal.ZERO;
            default                      -> new BigDecimal("0.50");
        };
        return scoreConfig.getDecimal(key, def).setScale(3, RoundingMode.HALF_UP);
    }

    /**
     * Setup allow-list per regime. Range/weak-downtrend are permissive enough to
     * run pullback setups only; panic blocks everything; bull unlocks breakout.
     */
    private List<String> resolveAllowedSetups(String regimeType) {
        return switch (regimeType) {
            case REGIME_BULL_TREND       -> List.of(SETUP_BREAKOUT, SETUP_PULLBACK, SETUP_EVENT_2ND);
            case REGIME_RANGE_CHOP       -> List.of(SETUP_PULLBACK, SETUP_EVENT_2ND);
            case REGIME_WEAK_DOWNTREND   -> List.of(SETUP_PULLBACK);
            case REGIME_PANIC_VOLATILITY -> List.of();
            default                      -> List.of();
        };
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static BigDecimal safe(BigDecimal v, BigDecimal fallback) {
        return v == null ? fallback : v;
    }

    private String serializeReasons(List<String> reasons) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (String r : reasons) arr.add(r);
        try {
            return objectMapper.writeValueAsString(arr);
        } catch (JsonProcessingException e) {
            log.warn("[MarketRegimeEngine] failed to serialize reasons", e);
            return "[]";
        }
    }

    private String serializeInput(MarketRegimeInput input) {
        ObjectNode obj = objectMapper.createObjectNode();
        obj.put("tradingDate",                input.tradingDate()  == null ? null : input.tradingDate().toString());
        obj.put("evaluatedAt",                input.evaluatedAt()  == null ? null : input.evaluatedAt().toString());
        obj.put("marketGrade",                input.marketGrade());
        obj.put("marketPhase",                input.marketPhase());
        putDecimal(obj, "tsmcTrendScore",           input.tsmcTrendScore());
        putDecimal(obj, "breadthPositiveRatio",     input.breadthPositiveRatio());
        putDecimal(obj, "breadthNegativeRatio",     input.breadthNegativeRatio());
        putDecimal(obj, "leadersStrongRatio",       input.leadersStrongRatio());
        putDecimal(obj, "indexDistanceFromMa10Pct", input.indexDistanceFromMa10Pct());
        putDecimal(obj, "indexDistanceFromMa20Pct", input.indexDistanceFromMa20Pct());
        putDecimal(obj, "intradayVolatilityPct",    input.intradayVolatilityPct());
        obj.put("washoutRebound",   input.washoutRebound());
        obj.put("nearHighNotBreak", input.nearHighNotBreak());
        obj.put("blowoffSignal",    input.blowoffSignal());
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("[MarketRegimeEngine] failed to serialize input snapshot", e);
            return "{}";
        }
    }

    private static void putDecimal(ObjectNode obj, String key, BigDecimal v) {
        if (v == null) obj.putNull(key);
        else           obj.put(key, v);
    }
}
