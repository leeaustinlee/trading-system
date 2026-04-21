package com.austin.trading.engine;

import com.austin.trading.engine.PositionDecisionEngine.PositionDecisionResult;
import com.austin.trading.engine.PositionDecisionEngine.PositionStatus;
import com.austin.trading.engine.PositionDecisionEngine.TrailingAction;
import org.springframework.stereotype.Component;

/**
 * P2.3 Exit-Regime Integration Engine.
 *
 * <p>Applied AFTER {@link PositionDecisionEngine} produces its base decision.
 * Overrides the base result when market regime or theme decay mandates earlier exit.</p>
 *
 * <h3>Override rules (highest priority first)</h3>
 * <ol>
 *   <li>PANIC_VOLATILITY regime → force EXIT (reason: REGIME_PANIC)</li>
 *   <li>WEAK_DOWNTREND regime + DECAY theme → force EXIT (reason: REGIME_WEAK_THEME_DECAY)</li>
 *   <li>DECAY theme + non-BULL regime + base status is HOLD/STRONG → downgrade to WEAKEN (reason: THEME_DECAY)</li>
 *   <li>Otherwise → no override (original result returned)</li>
 * </ol>
 *
 * <p>Does not override an already-EXIT or TRAIL_UP decision — those are more specific.</p>
 */
@Component
public class ExitRegimeIntegrationEngine {

    static final String REASON_REGIME_PANIC             = "REGIME_PANIC";
    static final String REASON_REGIME_WEAK_THEME_DECAY  = "REGIME_WEAK_THEME_DECAY";
    static final String REASON_THEME_DECAY              = "THEME_DECAY";

    /**
     * Apply regime/theme exit override if applicable.
     *
     * @param base       base decision from PositionDecisionEngine
     * @param regimeType current market regime type (nullable)
     * @param themeStage current theme stage for this position's theme (nullable)
     * @return overridden result, or {@code base} if no override applies
     */
    public PositionDecisionResult applyOverride(PositionDecisionResult base,
                                                 String regimeType, String themeStage) {
        if (base == null) return null;

        // Already exiting — don't override
        if (base.status() == PositionStatus.EXIT) return base;

        // Rule 1: PANIC_VOLATILITY → force EXIT
        if ("PANIC_VOLATILITY".equals(regimeType)) {
            return new PositionDecisionResult(
                    PositionStatus.EXIT, REASON_REGIME_PANIC, null, TrailingAction.NONE);
        }

        // Rule 2: WEAK_DOWNTREND + DECAY → force EXIT
        if ("WEAK_DOWNTREND".equals(regimeType) && "DECAY".equals(themeStage)) {
            return new PositionDecisionResult(
                    PositionStatus.EXIT, REASON_REGIME_WEAK_THEME_DECAY, null, TrailingAction.NONE);
        }

        // Rule 3: DECAY theme + non-BULL regime → downgrade STRONG/HOLD to WEAKEN
        boolean nonBull = !"BULL_TREND".equals(regimeType);
        boolean decayTheme = "DECAY".equals(themeStage);
        if (decayTheme && nonBull) {
            if (base.status() == PositionStatus.STRONG || base.status() == PositionStatus.HOLD) {
                return new PositionDecisionResult(
                        PositionStatus.WEAKEN, REASON_THEME_DECAY,
                        base.suggestedStopLoss(), base.trailingAction());
            }
        }

        return base;
    }

    /**
     * Convenience overload: accepts nullable strings; treats null as "no data" (no override).
     */
    public boolean wouldOverride(String regimeType, String themeStage) {
        if ("PANIC_VOLATILITY".equals(regimeType)) return true;
        if ("WEAK_DOWNTREND".equals(regimeType) && "DECAY".equals(themeStage)) return true;
        if ("DECAY".equals(themeStage) && !"BULL_TREND".equals(regimeType)) return true;
        return false;
    }
}
