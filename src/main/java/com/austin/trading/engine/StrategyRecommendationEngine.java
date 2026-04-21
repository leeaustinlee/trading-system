package com.austin.trading.engine;

import com.austin.trading.service.ScoreConfigService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 策略建議引擎。
 *
 * <p>從聚合統計產出參數調整建議。不直接改 config，只輸出建議。</p>
 * <p>sample size 保護：分組 < min_sample_size 時 confidence 降為 LOW 或跳過。</p>
 *
 * <p><b>P2.1 Bounded Learning</b>: 只有列在 {@code learning.allowed.keys} 白名單內的
 * {@code targetKey} 才能產出 PARAM_ADJUST / TAG_FREQUENCY / RISK_CONTROL 建議。
 * INFO / OBSERVATION 類型不受限制（不直接修改參數）。</p>
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

    /** Attribution-based stats fed from TradeAttributionService (P1.2/P2.1). */
    public record AttributionStats(
            BigDecimal timingPoorRate,
            BigDecimal exitPoorRate,
            BigDecimal avgDelayPct,
            Map<String, BigDecimal> setupTypeWinRates,
            int attributionCount
    ) {}

    // ── 主要邏輯 ──────────────────────────────────────────────────────────

    /** Analyze without attribution data (backward-compatible). */
    public List<Recommendation> analyze(AggregatedStats stats) {
        return analyze(stats, null);
    }

    /** Analyze with optional attribution data; applies bounded guard before returning. */
    public List<Recommendation> analyze(AggregatedStats stats, AttributionStats attrStats) {
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

        // 7. Attribution-based analyses (P2.1)
        if (attrStats != null && attrStats.attributionCount() >= config.getInt("learning.min_attribution_sample", 5)) {
            analyzeTimingQuality(attrStats, stats.currentConfig(), recs);
            analyzeSetupTypePerformance(attrStats, recs);
        }

        // P2.1 Bounded guard: filter out PARAM_ADJUST/TAG_FREQUENCY/RISK_CONTROL for non-approved keys
        return applyBoundedGuard(recs, stats.currentConfig());
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

    // ── P2.1 Attribution-based analyses ──────────────────────────────────────

    /**
     * If POOR timing rate exceeds threshold, suggest tightening timing.tolerance.delay_pct_max.
     */
    private void analyzeTimingQuality(AttributionStats attr, Map<String, String> cfg,
                                       List<Recommendation> recs) {
        if (attr.timingPoorRate() == null) return;
        BigDecimal threshold = new BigDecimal(
                cfg.getOrDefault("learning.timing_poor_rate_threshold", "30.0"));
        if (attr.timingPoorRate().compareTo(threshold) < 0) return;

        String currentStr = cfg.getOrDefault("timing.tolerance.delay_pct_max", "2.0");
        BigDecimal current = new BigDecimal(currentStr);
        BigDecimal suggested = current.subtract(new BigDecimal("0.5")).max(new BigDecimal("1.0"));

        recs.add(new Recommendation("PARAM_ADJUST", "timing.tolerance.delay_pct_max",
                currentStr, suggested.toPlainString(), "MEDIUM",
                "進場時機 POOR 佔比 " + attr.timingPoorRate().setScale(1, RoundingMode.HALF_UP)
                        + "% 超過門檻，建議收緊最大進場延遲容忍度",
                Map.of("timingPoorRate", attr.timingPoorRate(),
                       "avgDelayPct", attr.avgDelayPct() != null ? attr.avgDelayPct() : "N/A",
                       "attributionCount", attr.attributionCount())));
    }

    /**
     * Emit OBSERVATION for setup types with win rate below 30% (no parameter change).
     */
    private void analyzeSetupTypePerformance(AttributionStats attr, List<Recommendation> recs) {
        if (attr.setupTypeWinRates() == null || attr.setupTypeWinRates().isEmpty()) return;

        for (var entry : attr.setupTypeWinRates().entrySet()) {
            String setupType = entry.getKey();
            BigDecimal winRate = entry.getValue();
            if (winRate != null && winRate.compareTo(new BigDecimal("30")) < 0) {
                recs.add(new Recommendation("OBSERVATION", "setup.type." + setupType,
                        null, null, "LOW",
                        "Setup 類型 " + setupType + " 勝率僅 "
                                + winRate.setScale(1, RoundingMode.HALF_UP) + "%，建議檢視進場條件",
                        Map.of("setupType", setupType, "winRate", winRate)));
            }
        }
    }

    // ── P2.1 Bounded guard ────────────────────────────────────────────────────

    private static final Set<String> BOUNDED_TYPES = Set.of("PARAM_ADJUST", "TAG_FREQUENCY", "RISK_CONTROL");

    /**
     * Filters out parameter-changing recommendations whose targetKey is not in the
     * {@code learning.allowed.keys} whitelist. INFO and OBSERVATION always pass through.
     * If {@code learning.allowed.keys} is absent or blank, no filtering is applied (backward compat).
     */
    private List<Recommendation> applyBoundedGuard(List<Recommendation> recs, Map<String, String> cfg) {
        String allowedStr = cfg.getOrDefault("learning.allowed.keys", "");
        if (allowedStr.isBlank()) return recs;

        Set<String> allowed = Arrays.stream(allowedStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        return recs.stream()
                .filter(r -> !BOUNDED_TYPES.contains(r.recommendationType())
                             || allowed.contains(r.targetKey()))
                .toList();
    }
}
