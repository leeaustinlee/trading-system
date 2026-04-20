package com.austin.trading.engine;

import com.austin.trading.service.ScoreConfigService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 持股決策引擎（v1.0）。
 *
 * <p>對每一筆 OPEN position 評估，輸出 STRONG / HOLD / WEAKEN / EXIT / TRAIL_UP。</p>
 *
 * <h3>判斷優先序（由高到低）</h3>
 * <ol>
 *   <li>停損觸發 → EXIT</li>
 *   <li>failedBreakout + 虧損 → EXIT</li>
 *   <li>EXTREME 延伸 + 弱 → EXIT / WEAKEN</li>
 *   <li>持有過久且無動能 → EXIT</li>
 *   <li>Trailing stop 觸發 → TRAIL_UP</li>
 *   <li>題材轉弱 → WEAKEN</li>
 *   <li>MILD 延伸 → 最多 HOLD</li>
 *   <li>全部正面 → STRONG</li>
 *   <li>其餘 → HOLD</li>
 * </ol>
 */
@Component
public class PositionDecisionEngine {

    private final ScoreConfigService config;

    public PositionDecisionEngine(ScoreConfigService config) {
        this.config = config;
    }

    // ── Enums ──────────────────────────────────────────────────────────────

    public enum PositionStatus { STRONG, HOLD, WEAKEN, EXIT, TRAIL_UP }
    public enum TrailingAction { NONE, MOVE_TO_BREAKEVEN, MOVE_TO_FIRST, MOVE_TO_SECOND }
    public enum ExtendedLevel { NONE, MILD, EXTREME }

    // ── Input / Output ─────────────────────────────────────────────────────

    public record PositionDecisionInput(
            String symbol,
            BigDecimal entryPrice,
            BigDecimal currentStopLoss,
            BigDecimal takeProfit1,
            BigDecimal takeProfit2,
            BigDecimal trailingStopPrice,
            String side,
            int holdingDays,
            BigDecimal currentPrice,
            BigDecimal dayHigh,
            BigDecimal dayLow,
            BigDecimal prevClose,
            BigDecimal sessionHighPrice,    // 持倉期間最高價（用於 drawdown 計算）
            String marketGrade,
            Integer themeRank,
            BigDecimal finalThemeScore,
            BigDecimal unrealizedPnlPct,
            ExtendedLevel extendedLevel,
            boolean volumeWeakening,
            boolean failedBreakout,
            boolean momentumStrong,
            boolean nearResistance,
            boolean madeNewHighRecently
    ) {}

    public record PositionDecisionResult(
            PositionStatus status,
            String reason,
            BigDecimal suggestedStopLoss,
            TrailingAction trailingAction
    ) {}

    // ── 主要邏輯 ──────────────────────────────────────────────────────────

    public PositionDecisionResult evaluate(PositionDecisionInput in) {
        BigDecimal pnlPct = in.unrealizedPnlPct() != null ? in.unrealizedPnlPct() : BigDecimal.ZERO;

        // ─ 1. 停損觸發 → EXIT ──────────────────────────────────────────────
        BigDecimal effectiveStop = effectiveStopLoss(in);
        if (effectiveStop != null && in.currentPrice() != null
                && in.currentPrice().compareTo(effectiveStop) <= 0) {
            boolean trailingTriggered = in.trailingStopPrice() != null
                    && effectiveStop.compareTo(in.trailingStopPrice()) == 0;
            String label = trailingTriggered ? "跌破移動停利" : "觸發停損";
            return exit(label + " (stop=" + effectiveStop.toPlainString() + ")");
        }

        // ─ 2. 虧損超過上限 → EXIT ──────────────────────────────────────────
        BigDecimal exitLossPct = config.getDecimal("position.review.exit_loss_pct", new BigDecimal("6.0"));
        if (pnlPct.compareTo(exitLossPct.negate()) <= 0) {
            return exit("虧損超過上限 (" + pnlPct.toPlainString() + "%)");
        }

        // ─ 2.5. Drawdown 從日高回撤 → WEAKEN / EXIT ──────────────────────
        BigDecimal drawdownPct = computeDrawdownPct(in);
        if (drawdownPct != null) {
            BigDecimal ddExitPct = config.getDecimal("position.review.drawdown_from_high_exit_pct", new BigDecimal("5.0"));
            BigDecimal ddWeakenPct = config.getDecimal("position.review.drawdown_from_high_weaken_pct", new BigDecimal("3.0"));
            if (drawdownPct.compareTo(ddExitPct) >= 0) {
                return exit("從高點回撤 " + drawdownPct.setScale(1, RoundingMode.HALF_UP) + "% 超過出場門檻");
            }
            if (drawdownPct.compareTo(ddWeakenPct) >= 0 && !in.momentumStrong()) {
                return weaken("從高點回撤 " + drawdownPct.setScale(1, RoundingMode.HALF_UP) + "% 且動能不足");
            }
        }

        // ─ 3. failedBreakout + 虧損 → EXIT ────────────────────────────────
        if (in.failedBreakout() && pnlPct.compareTo(BigDecimal.ZERO) < 0) {
            return exit("假突破且虧損中");
        }

        // ─ 4. EXTREME 延伸 + 弱 → EXIT / WEAKEN ──────────────────────────
        if (in.extendedLevel() == ExtendedLevel.EXTREME) {
            boolean exitExtWeak = config.getBoolean("position.review.exit_if_extended_and_weak", true);
            if (exitExtWeak && (!in.momentumStrong() || in.failedBreakout())) {
                return exit("極度延伸且動能轉弱");
            }
            if (in.volumeWeakening()) {
                return weaken("極度延伸且量能轉弱");
            }
        }

        // ─ 5. 持有過久且無動能 → EXIT ─────────────────────────────────────
        int staleDays = config.getInt("position.review.stale_days_without_momentum", 7);
        int maxDays = config.getInt("position.review.max_holding_days", 15);

        if (in.holdingDays() >= maxDays && !in.momentumStrong()) {
            return exit("超過持有上限 (" + maxDays + " 天) 且無延續動能");
        }
        if (in.holdingDays() >= staleDays && !in.momentumStrong()
                && pnlPct.compareTo(new BigDecimal("3")) < 0) {
            return exit("持有 " + in.holdingDays() + " 天無動能且獲利不足");
        }

        // ─ 6. Trailing stop 判斷 → TRAIL_UP ──────────────────────────────
        TrailingAction trailAction = computeTrailingAction(in, pnlPct);
        if (trailAction != TrailingAction.NONE) {
            BigDecimal suggested = computeSuggestedStop(in, trailAction);
            // 只有新建議 > 現有停損才真的 TRAIL_UP
            if (suggested != null && (effectiveStop == null || suggested.compareTo(effectiveStop) > 0)) {
                return new PositionDecisionResult(
                        PositionStatus.TRAIL_UP,
                        "獲利達標，建議上移停損至 " + suggested.toPlainString(),
                        suggested, trailAction);
            }
        }

        // ─ 7. 題材轉弱 → WEAKEN ──────────────────────────────────────────
        BigDecimal weakenTheme = config.getDecimal("position.review.weaken_theme_score", new BigDecimal("6.0"));
        if (in.finalThemeScore() != null && in.finalThemeScore().compareTo(weakenTheme) < 0) {
            return weaken("題材分 " + in.finalThemeScore().toPlainString() + " 低於門檻");
        }
        if (in.volumeWeakening() && !in.momentumStrong()) {
            return weaken("量能轉弱且動能不足");
        }

        // ─ 8. MILD 延伸 → 最多 HOLD（不給 STRONG）────────────────────────
        if (in.extendedLevel() == ExtendedLevel.MILD) {
            boolean override = config.getBoolean("position.review.extended_weaken_override", true);
            if (override) {
                return hold("仍有獲利但已 MILD 延伸，維持警戒");
            }
        }

        // ─ 9. 全面正向 → STRONG ──────────────────────────────────────────
        if (isStrong(in, pnlPct)) {
            return strong("持股強勢：獲利中、題材強、動能續");
        }

        // ─ 10. 其餘 → HOLD ──────────────────────────────────────────────
        return hold("持股尚穩，持續觀察");
    }

    // ── 私有方法 ──────────────────────────────────────────────────────────

    private boolean isStrong(PositionDecisionInput in, BigDecimal pnlPct) {
        if (pnlPct.compareTo(BigDecimal.ZERO) <= 0) return false;
        if (!in.momentumStrong()) return false;
        if (in.failedBreakout() || in.volumeWeakening()) return false;
        if (in.extendedLevel() != ExtendedLevel.NONE) return false;

        int themeRankMax = config.getInt("position.review.theme_rank_max_for_strong", 3);
        BigDecimal themeScoreMin = config.getDecimal("position.review.theme_score_min_for_strong", new BigDecimal("7.0"));

        if (in.themeRank() != null && in.themeRank() > themeRankMax) return false;
        if (in.finalThemeScore() != null && in.finalThemeScore().compareTo(themeScoreMin) < 0) return false;

        return true;
    }

    /** 計算從持倉期間最高價到現價的回撤百分比，null 表示資料不足 */
    private BigDecimal computeDrawdownPct(PositionDecisionInput in) {
        BigDecimal high = in.sessionHighPrice();
        if (high == null || high.signum() <= 0 || in.currentPrice() == null) return null;
        if (in.currentPrice().compareTo(high) >= 0) return BigDecimal.ZERO; // 仍在高點
        return high.subtract(in.currentPrice())
                .divide(high, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    private BigDecimal effectiveStopLoss(PositionDecisionInput in) {
        if (in.trailingStopPrice() != null) {
            if (in.currentStopLoss() == null) return in.trailingStopPrice();
            return in.trailingStopPrice().max(in.currentStopLoss());
        }
        return in.currentStopLoss();
    }

    private TrailingAction computeTrailingAction(PositionDecisionInput in, BigDecimal pnlPct) {
        BigDecimal secondPct = config.getDecimal("position.trailing.second_trail_pct", new BigDecimal("8.0"));
        BigDecimal firstPct = config.getDecimal("position.trailing.first_trail_pct", new BigDecimal("5.0"));
        BigDecimal breakevenPct = config.getDecimal("position.trailing.breakeven_pct", new BigDecimal("3.0"));

        if (pnlPct.compareTo(secondPct) >= 0) return TrailingAction.MOVE_TO_SECOND;
        if (pnlPct.compareTo(firstPct) >= 0) return TrailingAction.MOVE_TO_FIRST;
        if (pnlPct.compareTo(breakevenPct) >= 0) return TrailingAction.MOVE_TO_BREAKEVEN;
        return TrailingAction.NONE;
    }

    private BigDecimal computeSuggestedStop(PositionDecisionInput in, TrailingAction action) {
        if (in.entryPrice() == null) return null;
        BigDecimal bufferPct = config.getDecimal("position.trailing.buffer_pct", new BigDecimal("1.5"));
        BigDecimal bufferMult = BigDecimal.ONE.add(bufferPct.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
        BigDecimal bufferMultDown = BigDecimal.ONE.subtract(bufferPct.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));

        return switch (action) {
            case MOVE_TO_BREAKEVEN -> in.entryPrice();
            case MOVE_TO_FIRST -> in.entryPrice().multiply(bufferMult).setScale(2, RoundingMode.HALF_UP);
            case MOVE_TO_SECOND -> {
                if (in.dayLow() != null) {
                    yield in.dayLow().multiply(bufferMultDown).setScale(2, RoundingMode.HALF_UP);
                }
                yield in.entryPrice().multiply(bufferMult).setScale(2, RoundingMode.HALF_UP);
            }
            case NONE -> null;
        };
    }

    private PositionDecisionResult exit(String reason) {
        return new PositionDecisionResult(PositionStatus.EXIT, reason, null, TrailingAction.NONE);
    }

    private PositionDecisionResult weaken(String reason) {
        return new PositionDecisionResult(PositionStatus.WEAKEN, reason, null, TrailingAction.NONE);
    }

    private PositionDecisionResult hold(String reason) {
        return new PositionDecisionResult(PositionStatus.HOLD, reason, null, TrailingAction.NONE);
    }

    private PositionDecisionResult strong(String reason) {
        return new PositionDecisionResult(PositionStatus.STRONG, reason, null, TrailingAction.NONE);
    }
}
