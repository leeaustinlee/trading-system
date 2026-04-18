package com.austin.trading.engine;

import com.austin.trading.service.ScoreConfigService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 觀察名單引擎（v1.0）。
 *
 * <p>根據候選股、題材、現有 watchlist 狀態，決定每檔股票的動作：
 * ADD / KEEP / PROMOTE_READY / DROP / EXPIRE。</p>
 *
 * <h3>核心設計</h3>
 * <ul>
 *   <li>TRACKING 門檻寬鬆，讓好股先進觀察池</li>
 *   <li>READY 門檻嚴格：需連續強勢 + 不延伸 + 不 cooldown + 題材更強</li>
 *   <li>Decay 機制：觀察天數過久逐漸降分（momentumStrong 豁免）</li>
 *   <li>isExtended 時不能升級 READY</li>
 * </ul>
 */
@Component
public class WatchlistEngine {

    private final ScoreConfigService config;

    public WatchlistEngine(ScoreConfigService config) {
        this.config = config;
    }

    // ── Enums ──────────────────────────────────────────────────────────────

    public enum WatchlistAction { ADD, KEEP, PROMOTE_READY, DROP, EXPIRE }

    // ── Input / Output ─────────────────────────────────────────────────────

    public record WatchlistEvaluationInput(
            String symbol,
            BigDecimal currentScore,
            BigDecimal previousHighestScore,
            String themeTag,
            Integer themeRank,
            BigDecimal finalThemeScore,
            int currentObservationDays,
            int currentConsecutiveStrongDays,
            String currentWatchStatus,
            boolean isVetoed,
            boolean isAlreadyHeld,
            boolean isAlreadyEntered,
            boolean isExtended,
            boolean momentumStrong,
            boolean failedBreakout,
            boolean inCooldown,
            String marketGrade              // A / B / C
    ) {}

    public record WatchlistEvaluationResult(
            WatchlistAction action,
            String reason
    ) {}

    // ── 主要邏輯 ──────────────────────────────────────────────────────────

    public WatchlistEvaluationResult evaluate(WatchlistEvaluationInput in) {
        // ─ 排除條件（無論現有狀態）──────────────────────────────────────
        if (in.isAlreadyHeld()) return drop("已持有該股");
        if (in.isAlreadyEntered()) return drop("已標記為 ENTERED");
        if (in.isVetoed()) return drop("被 VetoEngine 淘汰");

        // ─ 市場等級過濾 ────────────────────────────────────────────────
        String mg = in.marketGrade() != null ? in.marketGrade().toUpperCase() : "B";
        if ("C".equals(mg)) {
            boolean blockOnC = config.getBoolean("watchlist.market_grade_c_block_add", true);
            if (blockOnC && (in.currentWatchStatus() == null || in.currentWatchStatus().isBlank())) {
                return drop("市場等級 C，不新增觀察股");
            }
        }

        BigDecimal score = in.currentScore() != null ? in.currentScore() : BigDecimal.ZERO;
        BigDecimal adjustedScore = applyDecay(score, in.currentObservationDays(), in.momentumStrong());

        BigDecimal dropThreshold = config.getDecimal("watchlist.drop_score_threshold", new BigDecimal("5.0"));
        int maxObsDays = config.getInt("watchlist.max_observation_days", 10);

        // ─ 新股（不在 watchlist）→ 判斷是否 ADD ────────────────────────
        if (in.currentWatchStatus() == null || in.currentWatchStatus().isBlank()) {
            return evaluateAdd(in, score);
        }

        // ─ 現有 TRACKING / READY → 判斷 KEEP / PROMOTE / DROP / EXPIRE ──
        // 觀察過久 → EXPIRE
        if (in.currentObservationDays() >= maxObsDays
                && !"READY".equals(in.currentWatchStatus())) {
            return expire("觀察 " + in.currentObservationDays() + " 天未升級，到期移除");
        }

        // 分數過低 → DROP
        if (adjustedScore.compareTo(dropThreshold) < 0) {
            return drop("調整後分數 " + adjustedScore.toPlainString() + " 低於門檻");
        }

        // failedBreakout → DROP
        if (in.failedBreakout()) {
            return drop("出現假突破型態");
        }

        // 題材退潮 → DROP
        if (!meetsTrackingThemeConditions(in)) {
            return drop("題材排名或分數不再滿足追蹤條件");
        }

        // ─ 判斷是否可升級 READY ──────────────────────────────────────
        if ("TRACKING".equals(in.currentWatchStatus())) {
            if (canPromoteReady(in, adjustedScore)) {
                return new WatchlistEvaluationResult(WatchlistAction.PROMOTE_READY,
                        "連續強勢 " + in.currentConsecutiveStrongDays() + " 天，升級 READY");
            }
        }

        return new WatchlistEvaluationResult(WatchlistAction.KEEP,
                "持續追蹤 (adjusted=" + adjustedScore.toPlainString() + ")");
    }

    // ── 私有方法 ──────────────────────────────────────────────────────────

    private WatchlistEvaluationResult evaluateAdd(WatchlistEvaluationInput in, BigDecimal score) {
        BigDecimal minTrack = config.getDecimal("watchlist.min_score_to_track", new BigDecimal("6.0"));
        if (score.compareTo(minTrack) < 0) {
            return drop("分數 " + score.toPlainString() + " 未達追蹤門檻");
        }
        if (!meetsTrackingThemeConditions(in)) {
            return drop("題材條件不足，不加入追蹤");
        }
        return new WatchlistEvaluationResult(WatchlistAction.ADD,
                "分數達標 (" + score.toPlainString() + ")，加入觀察");
    }

    private boolean meetsTrackingThemeConditions(WatchlistEvaluationInput in) {
        int rankMax = config.getInt("watchlist.theme_rank_max_for_tracking", 5);
        BigDecimal scoreMin = config.getDecimal("watchlist.theme_score_min_for_tracking", new BigDecimal("6.0"));

        if (in.themeRank() != null && in.themeRank() > rankMax) return false;
        if (in.finalThemeScore() != null && in.finalThemeScore().compareTo(scoreMin) < 0) return false;
        return true;
    }

    private boolean canPromoteReady(WatchlistEvaluationInput in, BigDecimal adjustedScore) {
        // C 級市場不允許升級 READY
        String mg = in.marketGrade() != null ? in.marketGrade().toUpperCase() : "B";
        if ("C".equals(mg)) return false;

        BigDecimal minReady = config.getDecimal("watchlist.min_score_to_ready", new BigDecimal("7.0"));
        int minDays = config.getInt("watchlist.min_consecutive_strong_days", 2);
        int readyRankMax = config.getInt("watchlist.theme_rank_max_for_ready", 2);
        BigDecimal readyThemeMin = config.getDecimal("watchlist.theme_score_min_for_ready", new BigDecimal("7.0"));

        // B 級市場提高 READY 門檻
        if ("B".equals(mg)) {
            BigDecimal bonus = config.getDecimal("watchlist.market_grade_b_ready_score_bonus", new BigDecimal("0.5"));
            minReady = minReady.add(bonus);
        }

        // 分數門檻
        if (adjustedScore.compareTo(minReady) < 0) return false;

        // 連續強勢天數
        if (in.currentConsecutiveStrongDays() < minDays) return false;

        // 題材門檻（比 TRACKING 更嚴）
        if (in.themeRank() != null && in.themeRank() > readyRankMax) return false;
        if (in.finalThemeScore() != null && in.finalThemeScore().compareTo(readyThemeMin) < 0) return false;

        // 不可延伸
        if (in.isExtended()) return false;

        // 不可假突破
        if (in.failedBreakout()) return false;

        // 不可在冷卻中
        if (in.inCooldown()) return false;

        return true;
    }

    private BigDecimal applyDecay(BigDecimal score, int observationDays, boolean momentumStrong) {
        if (momentumStrong) return score;

        boolean enabled = config.getBoolean("watchlist.decay_enabled", true);
        if (!enabled) return score;

        int graceDays = config.getInt("watchlist.decay_grace_days", 3);
        BigDecimal decayPerDay = config.getDecimal("watchlist.decay_per_day", new BigDecimal("0.15"));

        if (observationDays <= graceDays) return score;

        int decayDays = observationDays - graceDays;
        BigDecimal penalty = decayPerDay.multiply(new BigDecimal(decayDays));
        BigDecimal adjusted = score.subtract(penalty);
        return adjusted.max(BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
    }

    private WatchlistEvaluationResult drop(String reason) {
        return new WatchlistEvaluationResult(WatchlistAction.DROP, reason);
    }

    private WatchlistEvaluationResult expire(String reason) {
        return new WatchlistEvaluationResult(WatchlistAction.EXPIRE, reason);
    }
}
