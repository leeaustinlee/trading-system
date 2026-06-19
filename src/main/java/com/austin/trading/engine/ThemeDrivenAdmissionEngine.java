package com.austin.trading.engine;

import com.austin.trading.domain.enums.ThemeAdmissionProductionAction;
import com.austin.trading.domain.enums.ThemeAdmissionShadowAction;
import com.austin.trading.entity.HotGroupStockSignalEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Pure, shadow-only admission evaluator for theme-driven signals.
 *
 * <p>This component performs no writes and must not be wired into production
 * candidate/watchlist/buy/sell/risk decision paths. It only describes what a
 * theme-first admission policy would have done for later observation.</p>
 */
@Component
public class ThemeDrivenAdmissionEngine {

    private static final BigDecimal STRONG_CHANGE_PCT = new BigDecimal("7");

    public ShadowDecision evaluate(HotGroupStockSignalEntity signal) {
        if (signal == null) {
            return reject("missing signal", false, false, false, false);
        }
        boolean limitRisk = Boolean.TRUE.equals(signal.getLimitRisk());
        boolean nearLimit = isNearLimit(signal);
        BigDecimal changePct = signal.getChangePct();
        String role = normalize(signal.getRole());

        if (isBlank(signal.getSymbol())) {
            return reject("missing symbol", limitRisk, nearLimit, false, false);
        }
        if (signal.getTradingDate() == null) {
            return reject("missing trading date", limitRisk, nearLimit, false, false);
        }
        if (isBlank(signal.getThemeTag())) {
            return reject("missing theme", limitRisk, nearLimit, false, false);
        }
        if (isBadTradability(signal.getTradabilityTag())) {
            return reject("bad tradability: " + signal.getTradabilityTag(), limitRisk, nearLimit, false, false);
        }

        boolean themeLeader = isThemeLeaderRole(role);
        boolean secondLeader = isSecondLeaderRole(role);
        boolean strongMover = changePct != null && changePct.compareTo(STRONG_CHANGE_PCT) >= 0;

        if (themeLeader) {
            if (limitRisk) {
                return new ShadowDecision(ThemeAdmissionShadowAction.WOULD_CREATE_PULLBACK_PLAN,
                        "theme leader with limit risk: shadow pullback plan", true, false, true, true, nearLimit);
            }
            return new ShadowDecision(ThemeAdmissionShadowAction.WOULD_ADMIT_CANDIDATE,
                    "theme leader: shadow candidate admission", true, false, false, false, nearLimit);
        }

        if (secondLeader || strongMover) {
            if (limitRisk) {
                return new ShadowDecision(ThemeAdmissionShadowAction.WOULD_CREATE_PULLBACK_PLAN,
                        (secondLeader ? "second leader" : "changePct >= 7") + " with limit risk: shadow pullback plan",
                        false, true, true, true, nearLimit);
            }
            return new ShadowDecision(ThemeAdmissionShadowAction.WOULD_ADMIT_WATCHLIST,
                    (secondLeader ? "second leader" : "changePct >= 7") + ": shadow watchlist admission",
                    false, true, false, false, nearLimit);
        }

        return new ShadowDecision(ThemeAdmissionShadowAction.SHADOW_ONLY,
                "follower/other role: observe only", false, false, false, false, nearLimit);
    }

    public ProductionDecision evaluateProduction(ProductionInput input) {
        if (input == null || input.signal() == null) {
            return new ProductionDecision(ThemeAdmissionProductionAction.REJECT_BAD_DATA, "missing signal");
        }
        HotGroupStockSignalEntity signal = input.signal();
        if (isBlank(signal.getSymbol())) {
            return new ProductionDecision(ThemeAdmissionProductionAction.REJECT_BAD_DATA, "missing symbol");
        }
        if (signal.getTradingDate() == null) {
            return new ProductionDecision(ThemeAdmissionProductionAction.REJECT_BAD_DATA, "missing trading date");
        }
        if (isBlank(signal.getThemeTag())) {
            return new ProductionDecision(ThemeAdmissionProductionAction.REJECT_WEAK_THEME, "missing theme");
        }
        if (Boolean.TRUE.equals(input.existingCandidate()) || Boolean.TRUE.equals(input.existingWatchlist())) {
            return new ProductionDecision(ThemeAdmissionProductionAction.SKIP_ALREADY_EXISTS, "candidate/watchlist already exists");
        }
        if (Boolean.TRUE.equals(signal.getLimitRisk())) {
            return new ProductionDecision(ThemeAdmissionProductionAction.SKIP_LIMIT_RISK, "limit risk remains shadow-only in P1-A");
        }
        if (isBadTradability(signal.getTradabilityTag()) || Boolean.TRUE.equals(input.suspended()) || Boolean.TRUE.equals(input.noPrice())) {
            return new ProductionDecision(ThemeAdmissionProductionAction.REJECT_BAD_DATA, "bad tradability/suspended/no price");
        }
        if (Boolean.FALSE.equals(input.liquidityPass())) {
            return new ProductionDecision(ThemeAdmissionProductionAction.REJECT_LIQUIDITY, "liquidity check failed");
        }
        if (isWeakTheme(signal.getRadarRankScore())) {
            return new ProductionDecision(ThemeAdmissionProductionAction.REJECT_WEAK_THEME, "weak or missing theme strength");
        }

        String role = normalize(signal.getRole());
        ShadowDecision shadow = evaluate(signal);
        if (isThemeLeaderRole(role) && shadow.action() == ThemeAdmissionShadowAction.WOULD_ADMIT_CANDIDATE) {
            return new ProductionDecision(ThemeAdmissionProductionAction.ADMIT_CANDIDATE, shadow.reason());
        }
        if (shadow.action() == ThemeAdmissionShadowAction.WOULD_ADMIT_WATCHLIST) {
            return new ProductionDecision(ThemeAdmissionProductionAction.ADMIT_WATCHLIST, shadow.reason());
        }
        return new ProductionDecision(ThemeAdmissionProductionAction.SHADOW_ONLY, shadow.reason());
    }

    private ShadowDecision reject(String reason, boolean limitRisk, boolean nearLimit,
                                  boolean leaderLike, boolean watchlistLike) {
        return new ShadowDecision(ThemeAdmissionShadowAction.REJECT, reason,
                leaderLike, watchlistLike, limitRisk, false, nearLimit);
    }

    private boolean isNearLimit(HotGroupStockSignalEntity signal) {
        if (Boolean.TRUE.equals(signal.getLimitRisk())) {
            return true;
        }
        return signal.getChangePct() != null && signal.getChangePct().compareTo(STRONG_CHANGE_PCT) >= 0;
    }

    private boolean isBadTradability(String tag) {
        String t = normalize(tag);
        return t.contains("SUSPEND") || t.contains("HALT") || t.contains("NO_PRICE") || t.contains("NO PRICE")
                || t.contains("NO_QUOTE") || t.contains("NO QUOTE");
    }

    private boolean isWeakTheme(BigDecimal score) {
        return score == null || score.compareTo(BigDecimal.ZERO) <= 0;
    }

    private boolean isThemeLeaderRole(String role) {
        return "THEME_LEADER".equals(role) || "STRONGEST_LEADER".equals(role);
    }

    private boolean isSecondLeaderRole(String role) {
        return "SECOND_LEADER".equals(role) || "THEME_SECOND_LEADER".equals(role);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public record ShadowDecision(
            ThemeAdmissionShadowAction action,
            String reason,
            boolean leaderLike,
            boolean watchlistLike,
            boolean limitRisk,
            boolean pullbackPlan,
            boolean nearLimit
    ) { }

    public record ProductionInput(
            HotGroupStockSignalEntity signal,
            Boolean existingCandidate,
            Boolean existingWatchlist,
            Boolean suspended,
            Boolean noPrice,
            Boolean liquidityPass
    ) { }

    public record ProductionDecision(
            ThemeAdmissionProductionAction action,
            String reason
    ) { }
}
