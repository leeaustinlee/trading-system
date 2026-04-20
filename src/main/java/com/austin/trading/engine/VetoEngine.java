package com.austin.trading.engine;

import com.austin.trading.domain.enums.StrategyType;
import com.austin.trading.service.ScoreConfigService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 硬性淘汰引擎（Veto）v2.3：支援 strategyType 分層。
 * <p>
 * SETUP 維持 v1.0 + v2.0 全部 hard veto；
 * MOMENTUM_CHASE 把部分流程性規則降為 scoring penalty，但保留核心風險 veto。
 * </p>
 *
 * <h3>Veto 原因代碼（v1.0 原有）</h3>
 * <ul>
 *   <li>MARKET_GRADE_C, DECISION_LOCKED, TIME_DECAY_LATE</li>
 *   <li>RR_BELOW_MIN, NOT_IN_FINAL_PLAN, NO_STOP_LOSS</li>
 *   <li>HIGH_VAL_WEAK_MARKET</li>
 * </ul>
 * <h3>v2.0 BC Sniper 新增</h3>
 * <ul>
 *   <li>NO_THEME, CODEX_SCORE_LOW, THEME_NOT_IN_TOP, THEME_SCORE_TOO_LOW</li>
 *   <li>SCORE_DIVERGENCE_HIGH, VOLUME_SPIKE_NO_BREAKOUT, ENTRY_TOO_EXTENDED</li>
 * </ul>
 * <h3>v2.3 Momentum 新增</h3>
 * <ul>
 *   <li>AI_STRONG_NEGATIVE — Claude 分數過低 / 含重大 riskFlag / Codex veto</li>
 * </ul>
 */
@Component
public class VetoEngine {

    private final ScoreConfigService config;

    public VetoEngine(ScoreConfigService config) {
        this.config = config;
    }

    public record VetoInput(
            String marketGrade,
            String decisionLock,
            String timeDecayStage,
            BigDecimal riskRewardRatio,
            Boolean includeInFinalPlan,
            BigDecimal stopLossPrice,
            String valuationMode,
            Boolean hasTheme,
            Integer themeRank,
            BigDecimal finalThemeScore,
            BigDecimal codexScore,
            BigDecimal javaScore,
            BigDecimal claudeScore,
            Boolean volumeSpike,
            Boolean priceNotBreakHigh,
            Boolean entryTooExtended,
            // v2.3 新欄位（SETUP 可忽略；MOMENTUM 用於 AI_STRONG_NEGATIVE 判定）
            List<String> claudeRiskFlags,
            Boolean codexVetoed
    ) {
        /** 向下相容 ctor：舊呼叫點不帶 v2.3 欄位。 */
        public VetoInput(String marketGrade, String decisionLock, String timeDecayStage,
                         BigDecimal riskRewardRatio, Boolean includeInFinalPlan,
                         BigDecimal stopLossPrice, String valuationMode,
                         Boolean hasTheme, Integer themeRank, BigDecimal finalThemeScore,
                         BigDecimal codexScore, BigDecimal javaScore, BigDecimal claudeScore,
                         Boolean volumeSpike, Boolean priceNotBreakHigh, Boolean entryTooExtended) {
            this(marketGrade, decisionLock, timeDecayStage, riskRewardRatio, includeInFinalPlan,
                    stopLossPrice, valuationMode, hasTheme, themeRank, finalThemeScore,
                    codexScore, javaScore, claudeScore, volumeSpike, priceNotBreakHigh, entryTooExtended,
                    null, null);
        }
    }

    /**
     * v2.3: scoringPenalty 給 MOMENTUM 用（SETUP 下一律 0）。
     */
    public record VetoResult(boolean vetoed, List<String> reasons, BigDecimal scoringPenalty) {
        public VetoResult(boolean vetoed, List<String> reasons) {
            this(vetoed, reasons, BigDecimal.ZERO);
        }
    }

    /** 舊 API：預設 SETUP。 */
    public VetoResult evaluate(VetoInput input) {
        return evaluate(input, StrategyType.SETUP);
    }

    /**
     * v2.3 主入口：依 strategyType 走對應分支。
     */
    public VetoResult evaluate(VetoInput input, StrategyType strategy) {
        if (strategy == StrategyType.MOMENTUM_CHASE) {
            return evaluateMomentum(input);
        }
        return evaluateSetup(input);
    }

    // ── SETUP 分支（原 v2.0 邏輯全保留）─────────────────────────────────────

    private VetoResult evaluateSetup(VetoInput input) {
        List<String> reasons = new ArrayList<>();

        if ("C".equalsIgnoreCase(input.marketGrade())) reasons.add("MARKET_GRADE_C");
        if ("LOCKED".equalsIgnoreCase(input.decisionLock())) reasons.add("DECISION_LOCKED");

        String lateStopGrade = config.getString("scoring.late_stop_market_grade", "A");
        if ("LATE".equalsIgnoreCase(input.timeDecayStage())) {
            boolean marketSufficient = "A".equalsIgnoreCase(input.marketGrade())
                    || ("B".equalsIgnoreCase(lateStopGrade) && "B".equalsIgnoreCase(input.marketGrade()));
            if (!marketSufficient) reasons.add("TIME_DECAY_LATE");
        }

        BigDecimal rrMin = "A".equalsIgnoreCase(input.marketGrade())
                ? config.getDecimal("scoring.rr_min_grade_a", new BigDecimal("2.2"))
                : config.getDecimal("scoring.rr_min_grade_b", new BigDecimal("2.5"));
        if (input.riskRewardRatio() != null && input.riskRewardRatio().compareTo(rrMin) < 0) {
            reasons.add("RR_BELOW_MIN");
        }

        if (!Boolean.TRUE.equals(input.includeInFinalPlan())) reasons.add("NOT_IN_FINAL_PLAN");
        if (input.stopLossPrice() == null) reasons.add("NO_STOP_LOSS");

        String vm = input.valuationMode();
        if (("VALUE_HIGH".equalsIgnoreCase(vm) || "VALUE_STORY".equalsIgnoreCase(vm))
                && !"A".equalsIgnoreCase(input.marketGrade())) {
            reasons.add("HIGH_VAL_WEAK_MARKET");
        }

        if (config.getBoolean("veto.require_theme", true) && !Boolean.TRUE.equals(input.hasTheme())) {
            reasons.add("NO_THEME");
        }

        BigDecimal codexMin = config.getDecimal("veto.codex_score_min", new BigDecimal("6.5"));
        if (input.codexScore() != null && input.codexScore().compareTo(codexMin) < 0) {
            reasons.add("CODEX_SCORE_LOW");
        }

        int themeRankMax = config.getInt("veto.theme_rank_max", 2);
        if (input.themeRank() != null && input.themeRank() > themeRankMax) {
            reasons.add("THEME_NOT_IN_TOP");
        }

        BigDecimal themeScoreMin = config.getDecimal("veto.final_theme_score_min", new BigDecimal("7.5"));
        if (input.finalThemeScore() != null && input.finalThemeScore().compareTo(themeScoreMin) < 0) {
            reasons.add("THEME_SCORE_TOO_LOW");
        }

        BigDecimal divergenceMax = config.getDecimal("veto.score_divergence_max", new BigDecimal("2.5"));
        if (input.javaScore() != null && input.claudeScore() != null) {
            if (input.javaScore().subtract(input.claudeScore()).abs().compareTo(divergenceMax) >= 0) {
                reasons.add("SCORE_DIVERGENCE_HIGH");
            }
        }

        if (Boolean.TRUE.equals(input.volumeSpike()) && Boolean.TRUE.equals(input.priceNotBreakHigh())) {
            reasons.add("VOLUME_SPIKE_NO_BREAKOUT");
        }

        if (Boolean.TRUE.equals(input.entryTooExtended())) reasons.add("ENTRY_TOO_EXTENDED");

        return new VetoResult(!reasons.isEmpty(), reasons, BigDecimal.ZERO);
    }

    // ── MOMENTUM_CHASE 分支 ─────────────────────────────────────────────────

    /**
     * MOMENTUM 規則表（參考 docs/momentum-chase-strategy-design.md §四）：
     * HARD_VETO：MARKET_GRADE_C、DECISION_LOCKED、SCORE_DIVERGENCE_HIGH、
     *           VOLUME_SPIKE_NO_BREAKOUT、AI_STRONG_NEGATIVE
     * PENALTY  ：TIME_DECAY_LATE、NOT_IN_FINAL_PLAN、HIGH_VAL_WEAK_MARKET、
     *           NO_THEME、CODEX_SCORE_LOW、THEME_NOT_IN_TOP、THEME_SCORE_TOO_LOW、
     *           ENTRY_TOO_EXTENDED
     * 跳過    ：RR_BELOW_MIN（Momentum 本質較差 RR）
     * 強制處理：NO_STOP_LOSS → 交由 Position 建立時用 momentum.stop_loss_pct 補停損
     */
    private VetoResult evaluateMomentum(VetoInput input) {
        List<String> hardReasons = new ArrayList<>();
        BigDecimal penalty = BigDecimal.ZERO;

        // HARD VETO
        if ("C".equalsIgnoreCase(input.marketGrade())) hardReasons.add("MARKET_GRADE_C");
        if ("LOCKED".equalsIgnoreCase(input.decisionLock())) hardReasons.add("DECISION_LOCKED");

        BigDecimal divergenceMax = config.getDecimal("veto.score_divergence_max", new BigDecimal("2.5"));
        if (input.javaScore() != null && input.claudeScore() != null) {
            if (input.javaScore().subtract(input.claudeScore()).abs().compareTo(divergenceMax) >= 0) {
                hardReasons.add("SCORE_DIVERGENCE_HIGH");
            }
        }

        if (Boolean.TRUE.equals(input.volumeSpike()) && Boolean.TRUE.equals(input.priceNotBreakHigh())) {
            hardReasons.add("VOLUME_SPIKE_NO_BREAKOUT");
        }

        // AI_STRONG_NEGATIVE
        if (isAiStronglyNegative(input)) {
            hardReasons.add("AI_STRONG_NEGATIVE");
        }

        // 若已有 hard veto，直接回（不需累加 penalty）
        if (!hardReasons.isEmpty()) {
            return new VetoResult(true, hardReasons, BigDecimal.ZERO);
        }

        // PENALTIES（累加到 scoringPenalty，附上 reason 作資訊）
        List<String> penaltyReasons = new ArrayList<>();

        // TIME_DECAY_LATE
        if ("LATE".equalsIgnoreCase(input.timeDecayStage())
                && !"A".equalsIgnoreCase(input.marketGrade())) {
            penalty = penalty.add(config.getDecimal("momentum.veto_penalty.time_decay_late",
                    new BigDecimal("0.5")));
            penaltyReasons.add("PENALTY:TIME_DECAY_LATE");
        }

        // NOT_IN_FINAL_PLAN
        if (!Boolean.TRUE.equals(input.includeInFinalPlan())) {
            penalty = penalty.add(config.getDecimal("momentum.veto_penalty.not_in_plan",
                    new BigDecimal("0.5")));
            penaltyReasons.add("PENALTY:NOT_IN_FINAL_PLAN");
        }

        // HIGH_VAL_WEAK_MARKET
        String vm = input.valuationMode();
        if (("VALUE_HIGH".equalsIgnoreCase(vm) || "VALUE_STORY".equalsIgnoreCase(vm))
                && !"A".equalsIgnoreCase(input.marketGrade())) {
            penalty = penalty.add(config.getDecimal("momentum.veto_penalty.high_val",
                    new BigDecimal("1.0")));
            penaltyReasons.add("PENALTY:HIGH_VAL_WEAK_MARKET");
        }

        // NO_THEME
        if (config.getBoolean("veto.require_theme", true) && !Boolean.TRUE.equals(input.hasTheme())) {
            penalty = penalty.add(config.getDecimal("momentum.veto_penalty.no_theme",
                    new BigDecimal("1.0")));
            penaltyReasons.add("PENALTY:NO_THEME");
        }

        // CODEX_SCORE_LOW
        BigDecimal codexMin = config.getDecimal("veto.codex_score_min", new BigDecimal("6.5"));
        if (input.codexScore() != null && input.codexScore().compareTo(codexMin) < 0) {
            penalty = penalty.add(config.getDecimal("momentum.veto_penalty.codex_low",
                    new BigDecimal("0.5")));
            penaltyReasons.add("PENALTY:CODEX_SCORE_LOW");
        }

        // THEME_NOT_IN_TOP
        int themeRankMax = config.getInt("veto.theme_rank_max", 2);
        if (input.themeRank() != null && input.themeRank() > themeRankMax) {
            penalty = penalty.add(config.getDecimal("momentum.veto_penalty.theme_not_in_top",
                    new BigDecimal("0.5")));
            penaltyReasons.add("PENALTY:THEME_NOT_IN_TOP");
        }

        // THEME_SCORE_TOO_LOW
        BigDecimal themeScoreMin = config.getDecimal("veto.final_theme_score_min", new BigDecimal("7.5"));
        if (input.finalThemeScore() != null && input.finalThemeScore().compareTo(themeScoreMin) < 0) {
            penalty = penalty.add(config.getDecimal("momentum.veto_penalty.theme_low",
                    new BigDecimal("0.5")));
            penaltyReasons.add("PENALTY:THEME_SCORE_TOO_LOW");
        }

        // ENTRY_TOO_EXTENDED
        if (Boolean.TRUE.equals(input.entryTooExtended())) {
            penalty = penalty.add(config.getDecimal("momentum.veto_penalty.extended",
                    new BigDecimal("1.0")));
            penaltyReasons.add("PENALTY:ENTRY_TOO_EXTENDED");
        }

        return new VetoResult(false, penaltyReasons, penalty);
    }

    private boolean isAiStronglyNegative(VetoInput input) {
        if (Boolean.TRUE.equals(input.codexVetoed())) return true;
        BigDecimal claudeMin = config.getDecimal("momentum.veto.claude_score_min", new BigDecimal("4.0"));
        if (input.claudeScore() != null && input.claudeScore().compareTo(claudeMin) < 0) return true;
        List<String> flags = input.claudeRiskFlags();
        if (flags != null && !flags.isEmpty()) {
            String hardCsv = config.getString("momentum.veto.risk_flag_hard",
                    "LIQUIDITY_TRAP,EARNINGS_MISS,INSIDER_SELLING,VOLUME_SPIKE_LONG_BLACK,SUSPENDED_WARN");
            for (String f : flags) {
                if (f == null) continue;
                for (String hard : hardCsv.split(",")) {
                    if (hard.trim().equalsIgnoreCase(f.trim())) return true;
                }
            }
        }
        return false;
    }
}
