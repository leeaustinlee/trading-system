package com.austin.trading.engine;

import com.austin.trading.service.ScoreConfigService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 加權評分引擎。
 * <p>
 * 依 score_config 中的 java_weight / claude_weight / codex_weight
 * 計算 ai_weighted_score 與 score_dispersion。
 * 若 Codex review 未啟用，codex_weight 轉給 java 與 claude 按比例分配。
 * </p>
 */
@Component
public class WeightedScoringEngine {

    private final ScoreConfigService config;

    public WeightedScoringEngine(ScoreConfigService config) {
        this.config = config;
    }

    /**
     * 計算加權 AI 綜合分（0~10）。
     *
     * @param javaScore   Java 結構評分（必填）
     * @param claudeScore Claude 研究評分（可 null 表示尚未研究）
     * @param codexScore  Codex 審核評分（可 null 表示未啟用）
     * @return 加權後分數，精度 3 位小數
     */
    public BigDecimal computeAiWeightedScore(BigDecimal javaScore, BigDecimal claudeScore, BigDecimal codexScore) {
        BigDecimal jw = config.getDecimal("scoring.java_weight",   new BigDecimal("0.40"));
        BigDecimal cw = config.getDecimal("scoring.claude_weight", new BigDecimal("0.35"));
        BigDecimal xw = config.getDecimal("scoring.codex_weight",  new BigDecimal("0.25"));

        boolean codexEnabled = config.getBoolean("scoring.enable_codex_review", true);

        // 若 Codex 未啟用或無分數，其權重按比例分給 java & claude
        BigDecimal totalWeight;
        BigDecimal sum = BigDecimal.ZERO;

        if (codexEnabled && codexScore != null) {
            totalWeight = jw.add(cw).add(xw);
            if (javaScore   != null) sum = sum.add(jw.multiply(javaScore));
            if (claudeScore != null) sum = sum.add(cw.multiply(claudeScore));
            sum = sum.add(xw.multiply(codexScore));
        } else {
            totalWeight = jw.add(cw);
            if (javaScore   != null) sum = sum.add(jw.multiply(javaScore));
            if (claudeScore != null) sum = sum.add(cw.multiply(claudeScore));
        }

        if (totalWeight.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return sum.divide(totalWeight, 3, RoundingMode.HALF_UP);
    }

    /**
     * 計算分數離散度（各 AI 分數之間的標準差）。
     * 離散度高表示 AI 意見分歧，應審慎。
     */
    public BigDecimal computeScoreDispersion(BigDecimal... scores) {
        List<BigDecimal> valid = Arrays.stream(scores).filter(Objects::nonNull).toList();
        if (valid.size() < 2) return BigDecimal.ZERO;

        BigDecimal count = new BigDecimal(valid.size());
        BigDecimal mean = valid.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(count, 6, RoundingMode.HALF_UP);

        BigDecimal variance = valid.stream()
                .map(s -> s.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(count, 6, RoundingMode.HALF_UP);

        return new BigDecimal(Math.sqrt(variance.doubleValue()))
                .setScale(3, RoundingMode.HALF_UP);
    }

    /**
     * 計算最終排序分（BC Sniper v2.0）。
     * <p>
     * 若已被 veto → 0；
     * 否則 final_rank_score = min(ai_weighted_score, consensus_score)。
     * 若 consensusScore 為 null（尚未計算），直接用 ai_weighted_score。
     * </p>
     */
    public BigDecimal computeFinalRankScore(BigDecimal aiWeightedScore, BigDecimal consensusScore, boolean isVetoed) {
        if (isVetoed) return BigDecimal.ZERO;
        BigDecimal weighted = aiWeightedScore != null ? aiWeightedScore : BigDecimal.ZERO;
        if (consensusScore == null) return weighted;
        return weighted.min(consensusScore);
    }
}
