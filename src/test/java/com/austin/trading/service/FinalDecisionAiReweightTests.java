package com.austin.trading.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * P0.1 AI default-score reweight 純邏輯測試。
 * <p>
 * 測試 {@link FinalDecisionService#applyAiDefaultReweight(BigDecimal, BigDecimal, BigDecimal,
 * BigDecimal, BigDecimal, BigDecimal)} 的權重轉移邏輯。
 * </p>
 *
 * <p>背景：當 Claude/Codex 沒有實際研究候選股時，會留下預設 3.00 分。
 * 直接套 0.40/0.35/0.25 加權會把 Java 的好分數稀釋掉（例 6.80*0.40+3.00*0.35+3.00*0.25 ≈ 4.52），
 * 全部候選股都會掉到 C 級。本邏輯把預設分對應的權重轉給 Java，避免稀釋。</p>
 */
class FinalDecisionAiReweightTests {

    private static final BigDecimal JW = new BigDecimal("0.40");
    private static final BigDecimal CW = new BigDecimal("0.35");
    private static final BigDecimal XW = new BigDecimal("0.25");

    @Test
    void bothAiDefault_reweightsAllToJava() {
        BigDecimal javaScore   = new BigDecimal("6.80");
        BigDecimal claudeScore = new BigDecimal("3.00");
        BigDecimal codexScore  = new BigDecimal("3.00");

        FinalDecisionService.AiWeightOverride override =
                FinalDecisionService.applyAiDefaultReweight(
                        javaScore, claudeScore, codexScore, JW, CW, XW);

        assertThat(override.javaW()).isEqualByComparingTo("1.00");
        assertThat(override.claudeW()).isEqualByComparingTo("0");
        assertThat(override.codexW()).isEqualByComparingTo("0");
        assertThat(override.reason()).isEqualTo("BOTH_AI_DEFAULT_JAVA_ONLY");

        // 等價於 final = javaScore（因為其他軸權重 = 0）
        BigDecimal finalScore = override.javaW().multiply(javaScore)
                .add(override.claudeW().multiply(claudeScore))
                .add(override.codexW().multiply(codexScore));
        assertThat(finalScore.doubleValue()).isCloseTo(6.80, within(0.001));
    }

    @Test
    void onlyClaudeDefault_movesClaudeWeightToJava_codexUnchanged() {
        BigDecimal javaScore   = new BigDecimal("6.80");
        BigDecimal claudeScore = new BigDecimal("3.00");   // 預設
        BigDecimal codexScore  = new BigDecimal("7.00");   // 已研究

        FinalDecisionService.AiWeightOverride override =
                FinalDecisionService.applyAiDefaultReweight(
                        javaScore, claudeScore, codexScore, JW, CW, XW);

        // java 應拿到 0.40 + 0.35 = 0.75；codex 還是 0.25
        assertThat(override.javaW()).isEqualByComparingTo("0.75");
        assertThat(override.claudeW()).isEqualByComparingTo("0");
        assertThat(override.codexW()).isEqualByComparingTo("0.25");
        assertThat(override.reason()).isEqualTo("CLAUDE_DEFAULT_REWEIGHT_TO_JAVA");

        // 權重總和守恆
        BigDecimal sum = override.javaW().add(override.claudeW()).add(override.codexW());
        assertThat(sum).isEqualByComparingTo("1.00");
    }

    @Test
    void onlyCodexDefault_movesCodexWeightToJava_claudeUnchanged() {
        BigDecimal javaScore   = new BigDecimal("6.80");
        BigDecimal claudeScore = new BigDecimal("7.50");   // 已研究
        BigDecimal codexScore  = new BigDecimal("3.00");   // 預設

        FinalDecisionService.AiWeightOverride override =
                FinalDecisionService.applyAiDefaultReweight(
                        javaScore, claudeScore, codexScore, JW, CW, XW);

        // java 應拿到 0.40 + 0.25 = 0.65；claude 還是 0.35
        assertThat(override.javaW()).isEqualByComparingTo("0.65");
        assertThat(override.claudeW()).isEqualByComparingTo("0.35");
        assertThat(override.codexW()).isEqualByComparingTo("0");
        assertThat(override.reason()).isEqualTo("CODEX_DEFAULT_REWEIGHT_TO_JAVA");

        // 權重總和守恆
        BigDecimal sum = override.javaW().add(override.claudeW()).add(override.codexW());
        assertThat(sum).isEqualByComparingTo("1.00");
    }

    @Test
    void neitherDefault_preservesOriginalWeights() {
        BigDecimal javaScore   = new BigDecimal("6.80");
        BigDecimal claudeScore = new BigDecimal("7.50");
        BigDecimal codexScore  = new BigDecimal("6.80");

        FinalDecisionService.AiWeightOverride override =
                FinalDecisionService.applyAiDefaultReweight(
                        javaScore, claudeScore, codexScore, JW, CW, XW);

        assertThat(override.javaW()).isEqualByComparingTo("0.40");
        assertThat(override.claudeW()).isEqualByComparingTo("0.35");
        assertThat(override.codexW()).isEqualByComparingTo("0.25");
        assertThat(override.reason()).isEqualTo("NO_OVERRIDE");
    }

    @Test
    void epsilon_treats3point001AsDefault() {
        // 浮點誤差容忍：3.0005 仍視為預設
        BigDecimal claudeScore = new BigDecimal("3.0005");
        BigDecimal codexScore  = new BigDecimal("3.000");

        FinalDecisionService.AiWeightOverride override =
                FinalDecisionService.applyAiDefaultReweight(
                        new BigDecimal("6.80"), claudeScore, codexScore, JW, CW, XW);

        assertThat(override.reason()).isEqualTo("BOTH_AI_DEFAULT_JAVA_ONLY");
    }

    @Test
    void epsilon_treats3point01AsRealScore() {
        // 3.01 與 3.00 差異 = 0.01 > epsilon(0.001)，應視為實際分數
        BigDecimal claudeScore = new BigDecimal("3.01");
        BigDecimal codexScore  = new BigDecimal("3.00");

        FinalDecisionService.AiWeightOverride override =
                FinalDecisionService.applyAiDefaultReweight(
                        new BigDecimal("6.80"), claudeScore, codexScore, JW, CW, XW);

        // claude=3.01 不是預設；codex=3.00 是預設 → CODEX_DEFAULT_REWEIGHT_TO_JAVA
        assertThat(override.reason()).isEqualTo("CODEX_DEFAULT_REWEIGHT_TO_JAVA");
        assertThat(override.javaW()).isEqualByComparingTo("0.65");
        assertThat(override.claudeW()).isEqualByComparingTo("0.35");
        assertThat(override.codexW()).isEqualByComparingTo("0");
    }

    @Test
    void nullScores_treatedAsRealScore_notDefault() {
        // null 並非預設 3.00；不該觸發 reweight
        FinalDecisionService.AiWeightOverride override =
                FinalDecisionService.applyAiDefaultReweight(
                        new BigDecimal("6.80"), null, null, JW, CW, XW);

        assertThat(override.reason()).isEqualTo("NO_OVERRIDE");
        assertThat(override.javaW()).isEqualByComparingTo("0.40");
        assertThat(override.claudeW()).isEqualByComparingTo("0.35");
        assertThat(override.codexW()).isEqualByComparingTo("0.25");
    }
}
