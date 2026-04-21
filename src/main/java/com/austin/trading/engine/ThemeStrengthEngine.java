package com.austin.trading.engine;

import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.ThemeStrengthDecision;
import com.austin.trading.dto.internal.ThemeStrengthInput;
import com.austin.trading.service.ScoreConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * P1.1 Theme Strength Engine — stateless, pure.
 *
 * <h3>Strength score formula</h3>
 * <pre>
 *   strengthScore = marketBehavior * w_mb
 *                 + claudeHeat      * w_heat
 *                 + claudeCont      * w_cont
 *                 + breadth         * w_breadth
 * </pre>
 *
 * <h3>Theme stage classification</h3>
 * <ul>
 *   <li>DECAY          — strengthScore &lt; 3.0 or decayRisk &gt; max_allowed</li>
 *   <li>EARLY_EXPANSION — 3.0 ≤ score &lt; 5.5</li>
 *   <li>MID_TREND      — 5.5 ≤ score &lt; 7.5</li>
 *   <li>LATE_EXTENSION — score ≥ 7.5</li>
 * </ul>
 *
 * <h3>Tradability rules</h3>
 * <ul>
 *   <li>decayRisk &gt; theme.decay.max_allowed → not tradable</li>
 *   <li>strengthScore &lt; theme.tradable.min_strength → not tradable</li>
 *   <li>hasRiskFlag = true and regime is not BULL_TREND → not tradable</li>
 * </ul>
 */
@Component
public class ThemeStrengthEngine {

    private static final Logger log = LoggerFactory.getLogger(ThemeStrengthEngine.class);

    static final String STAGE_DECAY           = "DECAY";
    static final String STAGE_EARLY_EXPANSION = "EARLY_EXPANSION";
    static final String STAGE_MID_TREND       = "MID_TREND";
    static final String STAGE_LATE_EXTENSION  = "LATE_EXTENSION";

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal TEN  = BigDecimal.TEN;

    private final ScoreConfigService config;
    private final ObjectMapper       objectMapper;

    public ThemeStrengthEngine(ScoreConfigService config, ObjectMapper objectMapper) {
        this.config       = config;
        this.objectMapper = objectMapper;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public ThemeStrengthDecision evaluate(ThemeStrengthInput in, MarketRegimeDecision regime) {
        if (in == null) return null;

        double wMb      = cfg("theme.weight.market_behavior", 0.40);
        double wHeat    = cfg("theme.weight.heat",            0.30);
        double wCont    = cfg("theme.weight.continuation",    0.20);
        double wBreadth = cfg("theme.weight.breadth",         0.10);

        double mb      = safe(in.marketBehaviorScore());
        double heat    = safe(in.claudeHeatScore());
        double cont    = safe(in.claudeContinuationScore());
        double breadth = safe(in.breadthScore());

        double raw = mb * wMb + heat * wHeat + cont * wCont + breadth * wBreadth;
        BigDecimal strengthScore = bd(raw).min(TEN).max(ZERO);

        BigDecimal decayRisk = computeDecayRisk(cont, heat, in.hasRiskFlag());

        double minStrength  = cfg("theme.tradable.min_strength", 3.0);
        double maxDecay     = cfg("theme.decay.max_allowed",     0.6);

        String themeStage = classifyStage(strengthScore.doubleValue(), decayRisk.doubleValue(), maxDecay);

        boolean tradable = isTradable(strengthScore.doubleValue(), decayRisk.doubleValue(),
                minStrength, maxDecay, in.hasRiskFlag(), regime);

        String reasonsJson = buildReasons(in, raw, mb, heat, cont, breadth,
                strengthScore, decayRisk, themeStage, tradable, regime);

        log.debug("[ThemeStrength] {} stage={} score={} decay={} tradable={}",
                in.themeTag(), themeStage, strengthScore.toPlainString(),
                decayRisk.toPlainString(), tradable);

        return new ThemeStrengthDecision(
                in.tradingDate(), in.themeTag(),
                strengthScore, themeStage, in.catalystType(),
                tradable, decayRisk, reasonsJson);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** decayRisk ∈ [0, 1] — increases when continuation low, heat low, or risk flag set. */
    private BigDecimal computeDecayRisk(double cont, double heat, boolean hasRiskFlag) {
        double base = (1.0 - cont / 10.0) * 0.40;
        double heatPenalty = heat < 3.0 ? 0.25 : 0.0;
        double riskPenalty = hasRiskFlag ? 0.35 : 0.0;
        double risk = Math.min(1.0, base + heatPenalty + riskPenalty);
        return bd(risk).setScale(4, RoundingMode.HALF_UP);
    }

    private String classifyStage(double score, double decay, double maxDecay) {
        if (score < 3.0 || decay > maxDecay) return STAGE_DECAY;
        if (score < 5.5) return STAGE_EARLY_EXPANSION;
        if (score < 7.5) return STAGE_MID_TREND;
        return STAGE_LATE_EXTENSION;
    }

    private boolean isTradable(double score, double decay,
                                double minStrength, double maxDecay,
                                boolean hasRiskFlag, MarketRegimeDecision regime) {
        if (decay > maxDecay)    return false;
        if (score < minStrength) return false;
        if (hasRiskFlag) {
            String rt = regime != null ? regime.regimeType() : "";
            return "BULL_TREND".equals(rt);
        }
        return true;
    }

    private String buildReasons(ThemeStrengthInput in, double rawScore,
                                 double mb, double heat, double cont, double breadth,
                                 BigDecimal strength, BigDecimal decay,
                                 String stage, boolean tradable, MarketRegimeDecision regime) {
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("themeTag",         in.themeTag());
            n.put("strengthScore",    strength.toPlainString());
            n.put("decayRisk",        decay.toPlainString());
            n.put("stage",            stage);
            n.put("tradable",         tradable);
            n.put("mb",               rawScore);
            n.put("input_mb",         mb);
            n.put("input_heat",       heat);
            n.put("input_cont",       cont);
            n.put("input_breadth",    breadth);
            n.put("hasRiskFlag",      in.hasRiskFlag());
            n.put("catalystType",     in.catalystType());
            n.put("regime",           regime != null ? regime.regimeType() : "UNKNOWN");
            return objectMapper.writeValueAsString(n);
        } catch (Exception e) {
            log.warn("[ThemeStrengthEngine] payload serialization failed");
            return "{}";
        }
    }

    private double cfg(String key, double defaultVal) {
        return config.getDecimal(key, BigDecimal.valueOf(defaultVal)).doubleValue();
    }

    private static double safe(BigDecimal v) {
        return (v == null || v.compareTo(ZERO) < 0) ? 0.0 : v.min(TEN).doubleValue();
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }
}
