package com.austin.trading.engine;

import com.austin.trading.domain.enums.StrategyType;
import com.austin.trading.service.ScoreConfigService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 硬性淘汰引擎（Veto）v2.6 MVP Refactor：SETUP 分支也採 hard + soft penalty 分流。
 *
 * <h3>SETUP 分流（v2.6 MVP）</h3>
 * <ul>
 *   <li>HARD VETO（真正風險紅線，觸發即 {@code vetoed=true}）：
 *       MARKET_GRADE_C、DECISION_LOCKED、TIME_DECAY_LATE、NO_STOP_LOSS、
 *       VOLUME_SPIKE_NO_BREAKOUT</li>
 *   <li>SOFT PENALTY（累加到 {@code scoringPenalty}，讓候選仍進入排序但分數下調）：
 *       RR_BELOW_MIN、NOT_IN_FINAL_PLAN、HIGH_VAL_WEAK_MARKET、NO_THEME、
 *       CODEX_SCORE_LOW、THEME_NOT_IN_TOP、THEME_SCORE_TOO_LOW、
 *       SCORE_DIVERGENCE_HIGH、ENTRY_TOO_EXTENDED</li>
 * </ul>
 *
 * <h3>MOMENTUM_CHASE 分流（v2.3）</h3>
 * <ul>
 *   <li>HARD：MARKET_GRADE_C、DECISION_LOCKED、SCORE_DIVERGENCE_HIGH、
 *       VOLUME_SPIKE_NO_BREAKOUT、AI_STRONG_NEGATIVE</li>
 *   <li>PENALTY：TIME_DECAY_LATE、NOT_IN_FINAL_PLAN、HIGH_VAL_WEAK_MARKET、
 *       NO_THEME、CODEX_SCORE_LOW、THEME_NOT_IN_TOP、THEME_SCORE_TOO_LOW、
 *       ENTRY_TOO_EXTENDED</li>
 * </ul>
 *
 * <p>扣分係數由 {@code penalty.*} / {@code momentum.veto_penalty.*} config 控制。</p>
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
     * v2.6 VetoResult：
     * <ul>
     *   <li>{@code vetoed} = 有 hard veto（語義與舊版相容）</li>
     *   <li>{@code reasons} = hardReasons + penaltyReasons（向下相容的合併 list）</li>
     *   <li>{@code scoringPenalty} = 扣分幅度（FinalDecisionEngine 會讀它做 finalRank 扣分）</li>
     *   <li>{@code hardReasons} = 新欄位：真正 hard veto 原因</li>
     *   <li>{@code penaltyReasons} = 新欄位：扣分類原因（以 {@code PENALTY:XXX} 前綴）</li>
     * </ul>
     */
    public record VetoResult(
            boolean vetoed,
            List<String> reasons,
            BigDecimal scoringPenalty,
            List<String> hardReasons,
            List<String> penaltyReasons
    ) {
        /** 舊 2-arg constructor（v2.3 及更早）：當 vetoed 時所有 reasons 歸 hard，否則歸 penalty。 */
        public VetoResult(boolean vetoed, List<String> reasons) {
            this(vetoed, reasons, BigDecimal.ZERO,
                    vetoed ? reasons : List.of(),
                    vetoed ? List.of() : reasons);
        }

        /** 舊 3-arg constructor（v2.3 MOMENTUM 用）：同上歸類邏輯。 */
        public VetoResult(boolean vetoed, List<String> reasons, BigDecimal scoringPenalty) {
            this(vetoed, reasons, scoringPenalty,
                    vetoed ? reasons : List.of(),
                    vetoed ? List.of() : reasons);
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

    // ── SETUP 分支（v2.6 MVP：hard + soft penalty 分流）──────────────────────

    private VetoResult evaluateSetup(VetoInput input) {
        List<String> hardReasons = new ArrayList<>();
        List<String> penaltyReasons = new ArrayList<>();
        BigDecimal penalty = BigDecimal.ZERO;

        // ─── HARD VETO（真正風險紅線）────────────────────────────────────
        if ("C".equalsIgnoreCase(input.marketGrade())) hardReasons.add("MARKET_GRADE_C");
        if ("LOCKED".equalsIgnoreCase(input.decisionLock())) hardReasons.add("DECISION_LOCKED");

        String lateStopGrade = config.getString("scoring.late_stop_market_grade", "A");
        if ("LATE".equalsIgnoreCase(input.timeDecayStage())) {
            boolean marketSufficient = "A".equalsIgnoreCase(input.marketGrade())
                    || ("B".equalsIgnoreCase(lateStopGrade) && "B".equalsIgnoreCase(input.marketGrade()));
            if (!marketSufficient) hardReasons.add("TIME_DECAY_LATE");
        }

        if (input.stopLossPrice() == null) hardReasons.add("NO_STOP_LOSS");

        if (Boolean.TRUE.equals(input.volumeSpike()) && Boolean.TRUE.equals(input.priceNotBreakHigh())) {
            hardReasons.add("VOLUME_SPIKE_NO_BREAKOUT");
        }

        // 已有 hard veto → 直接 hard block（不再累 penalty）
        if (!hardReasons.isEmpty()) {
            return new VetoResult(
                    true,
                    new ArrayList<>(hardReasons),
                    BigDecimal.ZERO,
                    hardReasons,
                    List.of()
            );
        }

        // ─── SOFT PENALTY（扣分但仍進入排序）────────────────────────────
        // RR_BELOW_MIN（P0.1 reviewer D.1：fallback 與 ScoreConfigService DEFAULTS 對齊）
        BigDecimal rrMin = "A".equalsIgnoreCase(input.marketGrade())
                ? config.getDecimal("scoring.rr_min_grade_a", new BigDecimal("2.0"))
                : config.getDecimal("scoring.rr_min_grade_b", new BigDecimal("1.8"));
        if (input.riskRewardRatio() != null && input.riskRewardRatio().compareTo(rrMin) < 0) {
            penalty = penalty.add(config.getDecimal("penalty.rr_below_min", new BigDecimal("1.5")));
            penaltyReasons.add("PENALTY:RR_BELOW_MIN");
        }

        // NOT_IN_FINAL_PLAN
        if (!Boolean.TRUE.equals(input.includeInFinalPlan())) {
            penalty = penalty.add(config.getDecimal("penalty.not_in_final_plan", new BigDecimal("0.5")));
            penaltyReasons.add("PENALTY:NOT_IN_FINAL_PLAN");
        }

        // HIGH_VAL_WEAK_MARKET
        String vm = input.valuationMode();
        if (("VALUE_HIGH".equalsIgnoreCase(vm) || "VALUE_STORY".equalsIgnoreCase(vm))
                && !"A".equalsIgnoreCase(input.marketGrade())) {
            penalty = penalty.add(config.getDecimal("penalty.high_val_weak_market", new BigDecimal("0.8")));
            penaltyReasons.add("PENALTY:HIGH_VAL_WEAK_MARKET");
        }

        // NO_THEME — v2.6：無論 veto.require_theme 設定如何，都改為 penalty
        // 保留 veto.require_theme config 作為「是否計算 NO_THEME penalty」開關
        if (config.getBoolean("veto.require_theme", false) && !Boolean.TRUE.equals(input.hasTheme())) {
            penalty = penalty.add(config.getDecimal("penalty.no_theme", new BigDecimal("1.0")));
            penaltyReasons.add("PENALTY:NO_THEME");
        }

        // CODEX_SCORE_LOW
        BigDecimal codexMin = config.getDecimal("veto.codex_score_min", new BigDecimal("6.5"));
        if (input.codexScore() != null && input.codexScore().compareTo(codexMin) < 0) {
            penalty = penalty.add(config.getDecimal("penalty.codex_low", new BigDecimal("1.0")));
            penaltyReasons.add("PENALTY:CODEX_SCORE_LOW");
        }

        // THEME_NOT_IN_TOP
        int themeRankMax = config.getInt("veto.theme_rank_max", 2);
        if (input.themeRank() != null && input.themeRank() > themeRankMax) {
            penalty = penalty.add(config.getDecimal("penalty.theme_not_top", new BigDecimal("0.8")));
            penaltyReasons.add("PENALTY:THEME_NOT_IN_TOP");
        }

        // THEME_SCORE_TOO_LOW
        BigDecimal themeScoreMin = config.getDecimal("veto.final_theme_score_min", new BigDecimal("7.5"));
        if (input.finalThemeScore() != null && input.finalThemeScore().compareTo(themeScoreMin) < 0) {
            penalty = penalty.add(config.getDecimal("penalty.theme_score_too_low", new BigDecimal("0.8")));
            penaltyReasons.add("PENALTY:THEME_SCORE_TOO_LOW");
        }

        // SCORE_DIVERGENCE_HIGH
        // v2.8 P0.9：改比 |claude - codex|（跟 ConsensusScoringEngine 同維度，避免 double penalty）
        // 原實作比 |java - claude|，但 v2.7 Consensus 去 Java 化後 java 不再是 AI 維度，
        // 加上 consensus 已對 claude/codex 分歧做 disagreement_penalty，不該再重覆扣。
        BigDecimal divergenceMax = config.getDecimal("veto.score_divergence_max", new BigDecimal("2.5"));
        if (input.claudeScore() != null && input.codexScore() != null) {
            if (input.claudeScore().subtract(input.codexScore()).abs().compareTo(divergenceMax) >= 0) {
                penalty = penalty.add(config.getDecimal("penalty.score_divergence_high", new BigDecimal("1.5")));
                penaltyReasons.add("PENALTY:SCORE_DIVERGENCE_HIGH");
            }
        }

        // ENTRY_TOO_EXTENDED — 未來應移到 timing 層；Phase 2 先當 penalty
        if (Boolean.TRUE.equals(input.entryTooExtended())) {
            penalty = penalty.add(config.getDecimal("penalty.entry_too_extended", new BigDecimal("1.0")));
            penaltyReasons.add("PENALTY:ENTRY_TOO_EXTENDED");
        }

        // 無 hard veto → vetoed=false，保留 penalty 供 FinalDecisionEngine 扣分
        return new VetoResult(
                false,
                new ArrayList<>(penaltyReasons),
                penalty,
                List.of(),
                penaltyReasons
        );
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

        // v2.8 P0.9：MOMENTUM 分支也改比 |claude - codex|（跟 SETUP 一致）
        BigDecimal divergenceMax = config.getDecimal("veto.score_divergence_max", new BigDecimal("2.5"));
        if (input.claudeScore() != null && input.codexScore() != null) {
            if (input.claudeScore().subtract(input.codexScore()).abs().compareTo(divergenceMax) >= 0) {
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
