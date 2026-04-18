package com.austin.trading.engine;

import com.austin.trading.service.ScoreConfigService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 硬性淘汰引擎（Veto）。
 * <p>
 * 所有 veto 規則在此統一執行。AI 評分不能覆蓋 veto。
 * 先 veto → 再排序，是本系統最重要的優先原則。
 * </p>
 *
 * Veto 原因代碼（v1.0 原有）：
 * <ul>
 *   <li>MARKET_GRADE_C       — 市場等級 C，禁止進場</li>
 *   <li>DECISION_LOCKED      — Decision Lock 啟動</li>
 *   <li>TIME_DECAY_LATE      — 時間衰減 LATE 且市場非 A</li>
 *   <li>RR_BELOW_MIN         — 風報比低於最低門檻</li>
 *   <li>NOT_IN_FINAL_PLAN    — 未標記納入最終計畫</li>
 *   <li>NO_STOP_LOSS         — 無合理停損設定</li>
 *   <li>HIGH_VAL_WEAK_MARKET — 高估值標的在弱市（VALUE_HIGH/STORY + 非 A）</li>
 * </ul>
 *
 * Veto 原因代碼（v2.0 BC Sniper 新增）：
 * <ul>
 *   <li>NO_THEME              — 無題材標籤（veto.require_theme=true 時）</li>
 *   <li>CODEX_SCORE_LOW       — Codex 分低於 veto.codex_score_min</li>
 *   <li>THEME_NOT_IN_TOP      — 題材排名超過 veto.theme_rank_max</li>
 *   <li>THEME_SCORE_TOO_LOW   — 題材分低於 veto.final_theme_score_min</li>
 *   <li>SCORE_DIVERGENCE_HIGH — Java/Claude 分差超過 veto.score_divergence_max</li>
 *   <li>VOLUME_SPIKE_NO_BREAKOUT — 爆量但未突破高點</li>
 *   <li>ENTRY_TOO_EXTENDED    — 進場位置距突破點太遠</li>
 * </ul>
 */
@Component
public class VetoEngine {

    private final ScoreConfigService config;

    public VetoEngine(ScoreConfigService config) {
        this.config = config;
    }

    public record VetoInput(
            // ── v1.0 原有欄位 ──────────────────────────────────────────────────
            String marketGrade,
            String decisionLock,
            String timeDecayStage,
            BigDecimal riskRewardRatio,
            Boolean includeInFinalPlan,
            BigDecimal stopLossPrice,
            String valuationMode,
            // ── v2.0 BC Sniper 新增欄位 ────────────────────────────────────────
            Boolean hasTheme,           // 是否有題材標籤
            Integer themeRank,          // 題材排名（null = 未查詢）
            BigDecimal finalThemeScore, // 題材最終分（null = 未查詢）
            BigDecimal codexScore,      // Codex 審核評分（null = 未啟用）
            BigDecimal javaScore,       // Java 結構評分（用於分歧計算）
            BigDecimal claudeScore,     // Claude 研究評分（用於分歧計算）
            Boolean volumeSpike,        // 爆量警示
            Boolean priceNotBreakHigh,  // 未突破近期高點
            Boolean entryTooExtended    // 進場位置過遠
    ) {}

    public record VetoResult(boolean vetoed, List<String> reasons) {}

    public VetoResult evaluate(VetoInput input) {
        List<String> reasons = new ArrayList<>();

        // ── v1.0 規則 ─────────────────────────────────────────────────────────

        // Rule 1: 市場等級 C
        if ("C".equalsIgnoreCase(input.marketGrade())) {
            reasons.add("MARKET_GRADE_C");
        }

        // Rule 2: Decision Lock 鎖定
        if ("LOCKED".equalsIgnoreCase(input.decisionLock())) {
            reasons.add("DECISION_LOCKED");
        }

        // Rule 3: 時間衰減 LATE 且市場非 A（可設定允許的最低等級）
        String lateStopGrade = config.getString("scoring.late_stop_market_grade", "A");
        if ("LATE".equalsIgnoreCase(input.timeDecayStage())) {
            boolean marketSufficient = "A".equalsIgnoreCase(input.marketGrade())
                    || ("B".equalsIgnoreCase(lateStopGrade) && "B".equalsIgnoreCase(input.marketGrade()));
            if (!marketSufficient) {
                reasons.add("TIME_DECAY_LATE");
            }
        }

        // Rule 4: 風報比低於門檻
        BigDecimal rrMin = "A".equalsIgnoreCase(input.marketGrade())
                ? config.getDecimal("scoring.rr_min_grade_a", new BigDecimal("2.2"))
                : config.getDecimal("scoring.rr_min_grade_b", new BigDecimal("2.5"));
        if (input.riskRewardRatio() != null && input.riskRewardRatio().compareTo(rrMin) < 0) {
            reasons.add("RR_BELOW_MIN");
        }

        // Rule 5: 未標記納入最終計畫
        if (!Boolean.TRUE.equals(input.includeInFinalPlan())) {
            reasons.add("NOT_IN_FINAL_PLAN");
        }

        // Rule 6: 無停損設定
        if (input.stopLossPrice() == null) {
            reasons.add("NO_STOP_LOSS");
        }

        // Rule 7: 高估值在弱市
        String vm = input.valuationMode();
        if (("VALUE_HIGH".equalsIgnoreCase(vm) || "VALUE_STORY".equalsIgnoreCase(vm))
                && !"A".equalsIgnoreCase(input.marketGrade())) {
            reasons.add("HIGH_VAL_WEAK_MARKET");
        }

        // ── v2.0 BC Sniper 新規則 ─────────────────────────────────────────────

        // Rule 8: 無題材標籤（require_theme=true 時）
        if (config.getBoolean("veto.require_theme", true) && !Boolean.TRUE.equals(input.hasTheme())) {
            reasons.add("NO_THEME");
        }

        // Rule 9: Codex 分低於門檻
        BigDecimal codexMin = config.getDecimal("veto.codex_score_min", new BigDecimal("6.5"));
        if (input.codexScore() != null && input.codexScore().compareTo(codexMin) < 0) {
            reasons.add("CODEX_SCORE_LOW");
        }

        // Rule 10: 題材排名超過門檻
        int themeRankMax = config.getInt("veto.theme_rank_max", 2);
        if (input.themeRank() != null && input.themeRank() > themeRankMax) {
            reasons.add("THEME_NOT_IN_TOP");
        }

        // Rule 11: 題材分低於門檻
        BigDecimal themeScoreMin = config.getDecimal("veto.final_theme_score_min", new BigDecimal("7.5"));
        if (input.finalThemeScore() != null && input.finalThemeScore().compareTo(themeScoreMin) < 0) {
            reasons.add("THEME_SCORE_TOO_LOW");
        }

        // Rule 12: Java / Claude 分歧過大
        BigDecimal divergenceMax = config.getDecimal("veto.score_divergence_max", new BigDecimal("2.5"));
        if (input.javaScore() != null && input.claudeScore() != null) {
            if (input.javaScore().subtract(input.claudeScore()).abs().compareTo(divergenceMax) >= 0) {
                reasons.add("SCORE_DIVERGENCE_HIGH");
            }
        }

        // Rule 13: 爆量但未突破高點
        if (Boolean.TRUE.equals(input.volumeSpike()) && Boolean.TRUE.equals(input.priceNotBreakHigh())) {
            reasons.add("VOLUME_SPIKE_NO_BREAKOUT");
        }

        // Rule 14: 進場位置距突破點太遠
        if (Boolean.TRUE.equals(input.entryTooExtended())) {
            reasons.add("ENTRY_TOO_EXTENDED");
        }

        return new VetoResult(!reasons.isEmpty(), reasons);
    }
}
