package com.austin.trading.engine;

import com.austin.trading.service.ScoreConfigService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Java 結構評分引擎。
 * <p>
 * 依照 DB 現有資料（無需即時報價）計算 java_structure_score（0-10）。
 * 評分維度：
 * <ol>
 *   <li>RR 風報比（0-4）：RR 越高分越高，上限 4.0</li>
 *   <li>計畫品質（0-2）：納入最終計畫 +1，有停損設定 +1</li>
 *   <li>進場型態（0-2）：BREAKOUT=2, REVERSAL=1.5, PULLBACK=1, 其他=0.5</li>
 *   <li>估值風險（0-1）：VALUE_LOW=1, VALUE_FAIR=0.8, VALUE_HIGH=0.4, VALUE_STORY=0.2</li>
 *   <li>加分（0-1）：有題材標籤 +0.5；基底分 >= 7 再 +0.5</li>
 * </ol>
 * 結果以 {@code min(sum, 10)} 封頂。
 * </p>
 */
@Component
public class JavaStructureScoringEngine {

    private final ScoreConfigService config;

    public JavaStructureScoringEngine(ScoreConfigService config) {
        this.config = config;
    }

    public record JavaStructureInput(
            BigDecimal riskRewardRatio,
            Boolean    includeInFinalPlan,
            BigDecimal stopLossPrice,
            String     valuationMode,
            String     entryType,
            BigDecimal baseScore,     // CandidateStockEntity.score（0-10）
            Boolean    hasTheme       // themeTag != null
    ) {}

    /**
     * @return java_structure_score (0-10)，精度 3 位小數
     */
    public BigDecimal compute(JavaStructureInput input) {
        double score = 0.0;

        // ── 1. RR 貢獻（0-4）─────────────────────────────────────────
        double rrMultiplier = config.getDecimal("scoring.java_rr_multiplier",
                new BigDecimal("1.5")).doubleValue();
        if (input.riskRewardRatio() != null) {
            score += Math.min(input.riskRewardRatio().doubleValue() * rrMultiplier, 4.0);
        }

        // ── 2. 計畫品質（0-2）─────────────────────────────────────────
        if (Boolean.TRUE.equals(input.includeInFinalPlan())) score += 1.0;
        if (input.stopLossPrice() != null)                    score += 1.0;

        // ── 3. 進場型態（0-2）─────────────────────────────────────────
        String et = input.entryType() == null ? "" : input.entryType().toUpperCase(Locale.ROOT).trim();
        score += switch (et) {
            case "BREAKOUT"  -> 2.0;
            case "REVERSAL"  -> 1.5;
            case "PULLBACK"  -> 1.0;
            default          -> 0.5;
        };

        // ── 4. 估值風險（0-1）─────────────────────────────────────────
        String vm = input.valuationMode() == null ? "" : input.valuationMode().toUpperCase(Locale.ROOT).trim();
        score += switch (vm) {
            case "VALUE_LOW"   -> 1.0;
            case "VALUE_FAIR"  -> 0.8;
            case "VALUE_HIGH"  -> 0.4;
            case "VALUE_STORY" -> 0.2;
            default            -> 0.5;
        };

        // ── 5. 加分（0-1）────────────────────────────────────────────
        if (Boolean.TRUE.equals(input.hasTheme())) score += 0.5;
        if (input.baseScore() != null && input.baseScore().doubleValue() >= 7.0) score += 0.5;

        double clamped = Math.max(0.0, Math.min(10.0, score));
        return BigDecimal.valueOf(clamped).setScale(3, RoundingMode.HALF_UP);
    }
}
