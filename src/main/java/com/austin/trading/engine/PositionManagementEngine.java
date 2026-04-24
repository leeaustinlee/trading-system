package com.austin.trading.engine;

import com.austin.trading.domain.enums.PositionAction;
import com.austin.trading.domain.enums.PositionSizeLevel;
import com.austin.trading.dto.internal.PositionManagementInput;
import com.austin.trading.dto.internal.PositionManagementInput.SwitchCandidate;
import com.austin.trading.dto.internal.PositionManagementResult;
import com.austin.trading.engine.PositionDecisionEngine.PositionStatus;
import com.austin.trading.service.ScoreConfigService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v2.10 Position Management MVP：對 OPEN 持倉產出 HOLD / ADD / REDUCE / EXIT / SWITCH_HINT。
 *
 * <h3>設計原則</h3>
 * <ul>
 *   <li>EXIT 最硬（stopLoss / trailingStop / PANIC / baselineStatus=EXIT）</li>
 *   <li>ADD 最嚴：只有續強 + 量價齊升才給，缺 VWAP / volumeRatio 不 ADD</li>
 *   <li>REDUCE 多個觸發訊號擇一即可（跌破 VWAP / 量縮 / 回吐 / 結構轉弱）</li>
 *   <li>SWITCH_HINT 只在 HOLD / REDUCE 情境下給；若已 EXIT，優先 EXIT</li>
 *   <li>資料缺失一律保守 fallback 到 HOLD（不誤殺持倉）</li>
 * </ul>
 */
@Component
public class PositionManagementEngine {

    // ── config keys ──
    private static final String KEY_ADD_MIN_PNL_PCT        = "position.mgmt.add_min_pnl_pct";
    private static final String KEY_ADD_MIN_VOL_RATIO      = "position.mgmt.add_min_volume_ratio";
    private static final String KEY_ADD_NEAR_HIGH_FACTOR   = "position.mgmt.add_near_high_factor";
    private static final String KEY_REDUCE_LOW_VOL_RATIO   = "position.mgmt.reduce_low_volume_ratio";
    private static final String KEY_REDUCE_GIVEBACK_PCT    = "position.mgmt.reduce_giveback_pct";
    private static final String KEY_SWITCH_SCORE_GAP       = "position.mgmt.switch_score_gap";

    // ── defaults（亦由 ScoreConfigService.DEFAULTS 種植入 DB）──
    private static final BigDecimal DEFAULT_ADD_MIN_PNL_PCT        = new BigDecimal("2.0");
    private static final BigDecimal DEFAULT_ADD_MIN_VOL_RATIO      = new BigDecimal("1.2");
    private static final BigDecimal DEFAULT_ADD_NEAR_HIGH_FACTOR   = new BigDecimal("0.995");
    private static final BigDecimal DEFAULT_REDUCE_LOW_VOL_RATIO   = new BigDecimal("0.8");
    private static final BigDecimal DEFAULT_REDUCE_GIVEBACK_PCT    = new BigDecimal("40");
    private static final BigDecimal DEFAULT_SWITCH_SCORE_GAP       = new BigDecimal("1.5");

    private final ScoreConfigService config;

    public PositionManagementEngine(ScoreConfigService config) {
        this.config = config;
    }

    public PositionManagementResult evaluate(PositionManagementInput in) {
        Map<String, Object> trace = new LinkedHashMap<>();
        List<String> signals = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        PositionSizeLevel sizeLevel = in.positionSizeLevel() == null
                ? PositionSizeLevel.NORMAL : in.positionSizeLevel();

        BigDecimal unrealizedPct = computeUnrealizedPct(in.currentPrice(), in.entryPrice());
        trace.put("symbol", in.symbol());
        trace.put("baselineStatus", in.baselineStatus() == null ? null : in.baselineStatus().name());
        trace.put("marketRegime", in.marketRegime());
        trace.put("currentPrice", in.currentPrice());
        trace.put("entryPrice", in.entryPrice());
        trace.put("unrealizedPct", unrealizedPct);
        trace.put("vwapPrice", in.vwapPrice());
        trace.put("volumeRatio", in.volumeRatio());
        trace.put("stopLoss", in.stopLoss());
        trace.put("trailingStop", in.trailingStop());
        trace.put("positionSizeLevel", sizeLevel.name());
        trace.put("peakUnrealizedPct", in.peakUnrealizedPct());
        trace.put("currentPositionScore", in.currentPositionScore());

        // ── EXIT（最高優先）────────────────────────────────────────────
        String exitReason = evaluateExit(in, signals);
        if (exitReason != null) {
            trace.put("decisionPath", "EXIT");
            return finalize(PositionAction.EXIT, exitReason, in, sizeLevel, unrealizedPct,
                    signals, warnings, trace);
        }

        // ── baselineStatus=EXIT 但未觸發硬指標：降級為 REDUCE ──
        if (in.baselineStatus() == PositionStatus.EXIT) {
            signals.add("BASELINE_EXIT");
            trace.put("decisionPath", "REDUCE_FROM_BASELINE_EXIT");
            return finalize(PositionAction.REDUCE, "REDUCE_BASELINE_EXIT_HINT", in,
                    sizeLevel, unrealizedPct, signals, warnings, trace);
        }

        // ── ADD（強條件一次全過才給）────────────────────────────────
        BigDecimal addMinPnl       = config.getDecimal(KEY_ADD_MIN_PNL_PCT,      DEFAULT_ADD_MIN_PNL_PCT);
        BigDecimal addMinVolRatio  = config.getDecimal(KEY_ADD_MIN_VOL_RATIO,    DEFAULT_ADD_MIN_VOL_RATIO);
        BigDecimal addNearHighFac  = config.getDecimal(KEY_ADD_NEAR_HIGH_FACTOR, DEFAULT_ADD_NEAR_HIGH_FACTOR);
        trace.put("addMinPnlPct", addMinPnl);
        trace.put("addMinVolumeRatio", addMinVolRatio);
        trace.put("addNearHighFactor", addNearHighFac);

        if (canAdd(in, sizeLevel, unrealizedPct, addMinPnl, addMinVolRatio, addNearHighFac, signals, warnings)) {
            trace.put("decisionPath", "ADD");
            return finalize(PositionAction.ADD, "ADD_STRONG_CONTINUATION", in,
                    sizeLevel, unrealizedPct, signals, warnings, trace);
        }

        // ── REDUCE（多訊號擇一）───────────────────────────────────────
        BigDecimal reduceLowVol = config.getDecimal(KEY_REDUCE_LOW_VOL_RATIO, DEFAULT_REDUCE_LOW_VOL_RATIO);
        BigDecimal giveBackPct  = config.getDecimal(KEY_REDUCE_GIVEBACK_PCT,  DEFAULT_REDUCE_GIVEBACK_PCT);
        trace.put("reduceLowVolumeRatio", reduceLowVol);
        trace.put("reduceGivebackPct", giveBackPct);

        String reduceReason = evaluateReduce(in, reduceLowVol, giveBackPct, signals);
        if (reduceReason != null) {
            // 若有更強同主題新候選，優先給 SWITCH_HINT 疊加（action 仍是 REDUCE）
            Map<String, Object> switchTrace = resolveSwitchHint(in);
            if (switchTrace != null) {
                trace.put("switchHint", switchTrace);
                signals.add("SWITCH_CANDIDATE_AVAILABLE");
                trace.put("decisionPath", "SWITCH_HINT");
                return finalize(PositionAction.SWITCH_HINT, "NEW_CANDIDATE_STRONGER", in,
                        sizeLevel, unrealizedPct, signals, warnings, trace);
            }
            trace.put("decisionPath", "REDUCE");
            return finalize(PositionAction.REDUCE, reduceReason, in,
                    sizeLevel, unrealizedPct, signals, warnings, trace);
        }

        // ── HOLD 時仍評估 SWITCH_HINT（同主題更強新候選）──
        Map<String, Object> switchTrace = resolveSwitchHint(in);
        if (switchTrace != null) {
            trace.put("switchHint", switchTrace);
            signals.add("SWITCH_CANDIDATE_AVAILABLE");
            trace.put("decisionPath", "SWITCH_HINT_FROM_HOLD");
            return finalize(PositionAction.SWITCH_HINT, "NEW_CANDIDATE_STRONGER", in,
                    sizeLevel, unrealizedPct, signals, warnings, trace);
        }

        // ── HOLD（預設）─────────────────────────────────────────────────
        signals.add("HOLD_TREND_INTACT");
        trace.put("decisionPath", "HOLD");
        return finalize(PositionAction.HOLD, "HOLD_TREND_INTACT", in,
                sizeLevel, unrealizedPct, signals, warnings, trace);
    }

    // ══════════════════════════════════════════════════════════════════════
    // EXIT rules
    // ══════════════════════════════════════════════════════════════════════

    private String evaluateExit(PositionManagementInput in, List<String> signals) {
        BigDecimal cur = in.currentPrice();
        if (cur == null) return null;

        if (in.stopLoss() != null && cur.compareTo(in.stopLoss()) <= 0) {
            signals.add("STOP_LOSS_HIT");
            return "EXIT_STOP_LOSS";
        }
        if (in.trailingStop() != null && cur.compareTo(in.trailingStop()) <= 0) {
            signals.add("TRAILING_STOP_HIT");
            return "EXIT_TRAILING_STOP";
        }
        if ("PANIC_VOLATILITY".equalsIgnoreCase(normalize(in.marketRegime()))) {
            signals.add("REGIME_PANIC");
            return "EXIT_PANIC";
        }
        // 結構破位 + 放量（用 volumeRatio>1.2 + baselineStatus=EXIT 判別）
        if (in.baselineStatus() == PositionStatus.EXIT
                && in.volumeRatio() != null
                && in.volumeRatio().compareTo(new BigDecimal("1.2")) > 0) {
            signals.add("STRUCTURE_BREAK_HIGH_VOLUME");
            return "EXIT_STRUCTURE_BREAK";
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════
    // ADD rules
    // ══════════════════════════════════════════════════════════════════════

    private boolean canAdd(
            PositionManagementInput in,
            PositionSizeLevel sizeLevel,
            BigDecimal unrealizedPct,
            BigDecimal addMinPnl,
            BigDecimal addMinVolRatio,
            BigDecimal addNearHighFactor,
            List<String> signals,
            List<String> warnings
    ) {
        // Gate 0：CORE 已滿、今日已加碼、總加碼次數 >= 1 → 不再 ADD（MVP：一檔最多加一次）
        if (sizeLevel == PositionSizeLevel.CORE) {
            warnings.add("SIZE_ALREADY_CORE");
            return false;
        }
        if (in.todayAddCount() > 0) {
            warnings.add("DAILY_ADD_LIMIT_REACHED");
            return false;
        }
        if (in.lifetimeAddCount() > 0) {
            warnings.add("LIFETIME_ADD_LIMIT_REACHED");
            return false;
        }

        // Gate 1：marketRegime 不可是 BEAR / PANIC
        String regime = normalize(in.marketRegime());
        if ("PANIC_VOLATILITY".equals(regime) || "WEAK_DOWNTREND".equals(regime) || "BEAR".equals(regime)) {
            warnings.add("REGIME_NOT_FRIENDLY");
            return false;
        }

        // Gate 2：浮盈 >= addMinPnl
        if (unrealizedPct == null || unrealizedPct.compareTo(addMinPnl) < 0) {
            warnings.add("PNL_BELOW_ADD_THRESHOLD");
            return false;
        }

        // Gate 3：currentPrice > VWAP（需要 VWAP）
        if (in.vwapPrice() == null || in.currentPrice() == null
                || in.currentPrice().compareTo(in.vwapPrice()) <= 0) {
            warnings.add("ADD_REQUIRES_ABOVE_VWAP");
            return false;
        }

        // Gate 4：volumeRatio >= addMinVolRatio
        if (in.volumeRatio() == null || in.volumeRatio().compareTo(addMinVolRatio) < 0) {
            warnings.add("ADD_REQUIRES_HIGH_VOLUME");
            return false;
        }

        // Gate 5：接近日高（currentPrice >= sessionHigh × factor）
        if (in.sessionHigh() == null
                || in.currentPrice().compareTo(in.sessionHigh().multiply(addNearHighFactor)) < 0) {
            warnings.add("ADD_REQUIRES_NEAR_HIGH");
            return false;
        }

        // Gate 6：baselineStatus 應是 STRONG 或 HOLD 或 TRAIL_UP
        if (in.baselineStatus() == PositionStatus.WEAKEN || in.baselineStatus() == PositionStatus.EXIT) {
            warnings.add("BASELINE_NOT_STRONG");
            return false;
        }

        signals.add("STRONG_CONTINUATION");
        signals.add("ABOVE_VWAP");
        signals.add("HIGH_VOLUME");
        signals.add("NEAR_HIGH");
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════
    // REDUCE rules
    // ══════════════════════════════════════════════════════════════════════

    private String evaluateReduce(
            PositionManagementInput in,
            BigDecimal reduceLowVol,
            BigDecimal giveBackPct,
            List<String> signals
    ) {
        // 1. currentPrice < VWAP
        if (in.vwapPrice() != null && in.currentPrice() != null
                && in.currentPrice().compareTo(in.vwapPrice()) < 0) {
            signals.add("BELOW_VWAP");
            return "REDUCE_BELOW_VWAP";
        }
        // 2. volumeRatio < 0.8
        if (in.volumeRatio() != null && in.volumeRatio().compareTo(reduceLowVol) < 0) {
            signals.add("LOW_VOLUME");
            return "REDUCE_LOW_VOLUME";
        }
        // 3. 浮盈從最高點回吐 >= 40%
        BigDecimal pct = computeUnrealizedPct(in.currentPrice(), in.entryPrice());
        if (in.peakUnrealizedPct() != null
                && in.peakUnrealizedPct().compareTo(BigDecimal.ZERO) > 0
                && pct != null) {
            BigDecimal giveBack = in.peakUnrealizedPct().subtract(pct);
            BigDecimal threshold = in.peakUnrealizedPct()
                    .multiply(giveBackPct).divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
            if (giveBack.compareTo(threshold) >= 0) {
                signals.add("PROFIT_GIVEBACK_OVER_THRESHOLD");
                return "REDUCE_PROFIT_GIVEBACK";
            }
        }
        // 4. 底層結構 WEAKEN
        if (in.baselineStatus() == PositionStatus.WEAKEN) {
            signals.add("BASELINE_WEAKEN");
            return "REDUCE_MOMENTUM_WEAKEN";
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════
    // SWITCH_HINT rules
    // ══════════════════════════════════════════════════════════════════════

    private Map<String, Object> resolveSwitchHint(PositionManagementInput in) {
        if (in.switchCandidates() == null || in.switchCandidates().isEmpty()) return null;
        if (in.currentPositionScore() == null) return null;

        BigDecimal scoreGap = config.getDecimal(KEY_SWITCH_SCORE_GAP, DEFAULT_SWITCH_SCORE_GAP);
        BigDecimal required = in.currentPositionScore().add(scoreGap);

        SwitchCandidate best = null;
        for (SwitchCandidate cand : in.switchCandidates()) {
            if (cand == null || cand.finalRankScore() == null) continue;
            if (!isBuyNow(cand.bucket())) continue;
            if (cand.finalRankScore().compareTo(required) < 0) continue;
            if (best == null || cand.finalRankScore().compareTo(best.finalRankScore()) > 0) {
                best = cand;
            }
        }
        if (best == null) return null;

        BigDecimal gap = best.finalRankScore().subtract(in.currentPositionScore());
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("switchFrom", in.symbol());
        hint.put("switchTo", best.symbol());
        hint.put("scoreGap", gap);
        hint.put("bucket", best.bucket());
        hint.put("themeTag", best.themeTag());
        hint.put("mainStream", best.mainStream());
        hint.put("reason", "NEW_CANDIDATE_STRONGER");
        return hint;
    }

    private boolean isBuyNow(String bucket) {
        if (bucket == null) return false;
        String b = bucket.trim().toUpperCase(Locale.ROOT);
        return "SELECT_BUY_NOW".equals(b) || "CONVERT_BUY".equals(b);
    }

    // ══════════════════════════════════════════════════════════════════════
    // helpers
    // ══════════════════════════════════════════════════════════════════════

    private BigDecimal computeUnrealizedPct(BigDecimal current, BigDecimal entry) {
        if (current == null || entry == null || entry.signum() == 0) return null;
        return current.subtract(entry)
                .divide(entry, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private String normalize(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private PositionManagementResult finalize(
            PositionAction action,
            String reason,
            PositionManagementInput in,
            PositionSizeLevel sizeLevel,
            BigDecimal unrealizedPct,
            List<String> signals,
            List<String> warnings,
            Map<String, Object> trace
    ) {
        trace.put("positionAction", action.name());
        trace.put("reason", reason);
        trace.put("signals", List.copyOf(signals));
        trace.put("warnings", List.copyOf(warnings));
        return new PositionManagementResult(
                in.symbol(),
                action,
                reason,
                in.currentPrice(),
                in.entryPrice(),
                unrealizedPct,
                in.vwapPrice(),
                in.volumeRatio(),
                in.stopLoss(),
                in.trailingStop(),
                in.currentPositionScore(),
                sizeLevel,
                List.copyOf(signals),
                List.copyOf(warnings),
                Collections.unmodifiableMap(new LinkedHashMap<>(trace))   // 保留 null 值，避免 Map.copyOf NPE
        );
    }
}
