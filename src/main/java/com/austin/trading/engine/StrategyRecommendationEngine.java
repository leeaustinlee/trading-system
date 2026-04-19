package com.austin.trading.engine;

import com.austin.trading.service.ScoreConfigService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略建議引擎。
 *
 * <p>從聚合統計產出參數調整建議。不直接改 config，只輸出建議。</p>
 * <p>sample size 保護：分組 < min_sample_size 時 confidence 降為 LOW 或跳過。</p>
 */
@Component
public class StrategyRecommendationEngine {

    private final ScoreConfigService config;

    public StrategyRecommendationEngine(ScoreConfigService config) {
        this.config = config;
    }

    // ── Input / Output ─────────────────────────────────────────────────────

    public record AggregatedStats(
            BigDecimal overallWinRate,
            BigDecimal avgReturn,
            int totalTrades,
            Map<String, BigDecimal> winRateByTag,
            Map<String, BigDecimal> avgReturnByTag,
            Map<String, Integer> countByTag,
            BigDecimal avgMfePct,
            BigDecimal avgMaePct,
            BigDecimal avgHoldingDays,
            int consecutiveLossMax,
            Map<String, String> currentConfig
    ) {}

    public record Recommendation(
            String recommendationType,
            String targetKey,
            String currentValue,
            String suggestedValue,
            String confidenceLevel,
            String reason,
            Map<String, Object> supportingMetrics
    ) {}

    // ── 主要邏輯 ──────────────────────────────────────────────────────────

    public List<Recommendation> analyze(AggregatedStats stats) {
        List<Recommendation> recs = new ArrayList<>();
        int minSample = config.getInt("recommendation.min_sample_size", 10);

        if (stats.totalTrades() < minSample) {
            recs.add(new Recommendation("INFO", "N/A", null, null, "LOW",
                    "交易筆數 " + stats.totalTrades() + " 不足 " + minSample + " 筆，建議暫不調整參數",
                    Map.of("totalTrades", stats.totalTrades(), "minRequired", minSample)));
            return recs;
        }

        // 1. 追高交易太多 → 建議收緊 isExtended 過濾
        analyzeTagProblem(stats, "CHASED_TOO_HIGH", minSample, recs,
                "position.review.exit_if_extended_and_weak", "true", "true",
                "追高交易佔比過高，建議啟用延伸位置出場");

        // 2. 假突破太多 → 建議啟用 entry trigger
        analyzeTagProblem(stats, "FAILED_BREAKOUT_ENTRY", minSample, recs,
                "decision.require_entry_trigger", "true", "true",
                "假突破進場佔比過高，建議要求進場觸發確認");

        // 3. 持有過久 → 建議縮短 max_holding_days
        analyzeHoldingTooLong(stats, minSample, recs);

        // 4. 利潤回吐 → 建議收緊 trailing stop
        analyzeProfitGiveback(stats, minSample, recs);

        // 5. 連續虧損 → 建議收緊 cooldown
        analyzeConsecutiveLoss(stats, recs);

        // 6. 勝率分析 → 整體建議
        analyzeOverallWinRate(stats, recs);

        return recs;
    }

    // ── 私有方法 ──────────────────────────────────────────────────────────

    private void analyzeTagProblem(AggregatedStats stats, String tag, int minSample,
                                    List<Recommendation> recs,
                                    String targetKey, String currentDefault, String suggested,
                                    String reason) {
        Integer count = stats.countByTag().getOrDefault(tag, 0);
        if (count < 3) return; // 太少不分析

        BigDecimal ratio = new BigDecimal(count)
                .divide(new BigDecimal(stats.totalTrades()), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        if (ratio.compareTo(new BigDecimal("15")) >= 0) {
            String confidence = count >= minSample ? "MEDIUM" : "LOW";
            String current = stats.currentConfig().getOrDefault(targetKey, currentDefault);
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("tagCount", count);
            metrics.put("tagRatio", ratio);
            metrics.put("totalTrades", stats.totalTrades());

            recs.add(new Recommendation("TAG_FREQUENCY", targetKey,
                    current, suggested, confidence, reason + " (佔比 " + ratio.setScale(1, RoundingMode.HALF_UP) + "%)", metrics));
        }
    }

    private void analyzeHoldingTooLong(AggregatedStats stats, int minSample, List<Recommendation> recs) {
        Integer heldCount = stats.countByTag().getOrDefault("HELD_TOO_LONG", 0);
        if (heldCount < 3) return;

        BigDecimal ratio = new BigDecimal(heldCount)
                .divide(new BigDecimal(stats.totalTrades()), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        if (ratio.compareTo(new BigDecimal("20")) >= 0 && stats.avgHoldingDays() != null) {
            String current = stats.currentConfig().getOrDefault("position.review.max_holding_days", "15");
            int currentDays = Integer.parseInt(current);
            int suggested = Math.max(5, currentDays - 2);
            String confidence = heldCount >= minSample ? "MEDIUM" : "LOW";

            recs.add(new Recommendation("PARAM_ADJUST", "position.review.max_holding_days",
                    current, String.valueOf(suggested), confidence,
                    "持有過久佔比 " + ratio.setScale(1, RoundingMode.HALF_UP) + "%，建議縮短持有上限",
                    Map.of("heldTooLongCount", heldCount, "avgHoldingDays", stats.avgHoldingDays())));
        }
    }

    private void analyzeProfitGiveback(AggregatedStats stats, int minSample, List<Recommendation> recs) {
        Integer givebackCount = stats.countByTag().getOrDefault("STRONG_HOLD_BUT_GAVE_BACK_PROFIT", 0);
        if (givebackCount < 3) return;

        BigDecimal ratio = new BigDecimal(givebackCount)
                .divide(new BigDecimal(stats.totalTrades()), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        if (ratio.compareTo(new BigDecimal("15")) >= 0) {
            String current = stats.currentConfig().getOrDefault("position.trailing.first_trail_pct", "5.0");
            BigDecimal currentPct = new BigDecimal(current);
            BigDecimal suggested = currentPct.subtract(new BigDecimal("1.0")).max(new BigDecimal("3.0"));
            String confidence = givebackCount >= minSample ? "MEDIUM" : "LOW";

            recs.add(new Recommendation("PARAM_ADJUST", "position.trailing.first_trail_pct",
                    current, suggested.toPlainString(), confidence,
                    "利潤回吐佔比 " + ratio.setScale(1, RoundingMode.HALF_UP) + "%，建議提前啟動移動停利",
                    Map.of("givebackCount", givebackCount, "avgMfe", stats.avgMfePct())));
        }
    }

    private void analyzeConsecutiveLoss(AggregatedStats stats, List<Recommendation> recs) {
        if (stats.consecutiveLossMax() >= 4) {
            String current = stats.currentConfig().getOrDefault("trading.cooldown.consecutive_loss_max", "3");
            recs.add(new Recommendation("RISK_CONTROL", "trading.cooldown.consecutive_loss_max",
                    current, "2", "HIGH",
                    "最大連續虧損達 " + stats.consecutiveLossMax() + " 筆，建議收緊連續虧損上限",
                    Map.of("consecutiveLossMax", stats.consecutiveLossMax())));
        }
    }

    private void analyzeOverallWinRate(AggregatedStats stats, List<Recommendation> recs) {
        if (stats.overallWinRate() == null) return;

        if (stats.overallWinRate().compareTo(new BigDecimal("50")) < 0) {
            recs.add(new Recommendation("OBSERVATION", "scoring.grade_ap_min",
                    stats.currentConfig().getOrDefault("scoring.grade_ap_min", "8.8"), null, "LOW",
                    "整體勝率 " + stats.overallWinRate().setScale(1, RoundingMode.HALF_UP)
                            + "% 偏低，建議檢視進場門檻是否需提高",
                    Map.of("winRate", stats.overallWinRate(), "totalTrades", stats.totalTrades())));
        }
    }
}
