package com.austin.trading.engine;

import com.austin.trading.service.ScoreConfigService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 共識評分引擎（v2.8 P0.9：單邊 AI 支援 + aiConfidenceMode trace）。
 *
 * <h3>v2.8 算法</h3>
 * <pre>
 * // FULL_AI（claude + codex 都有）
 * consensus = max(min(claude, codex) - |claude-codex|*cx, 0)
 * mode      = FULL_AI
 *
 * // CLAUDE_ONLY（只有 claude）
 * consensus = claude        （合理 fallback：單邊 AI 正向）
 * mode      = CLAUDE_ONLY
 *
 * // CODEX_ONLY（只有 codex）
 * consensus = codex
 * mode      = CODEX_ONLY
 *
 * // AI_MISSING（兩邊都沒）
 * consensus = null          （呼叫方 fallback 到 weighted / java-only）
 * mode      = AI_MISSING
 * </pre>
 *
 * <p>Java 結構分不進共識（v2.7 去 Java 化保留）。</p>
 *
 * <h3>Config</h3>
 * <ul>
 *   <li>{@code consensus.penalty_cx} — Claude 與 Codex 分歧懲罰係數（預設 0.20）</li>
 *   <li>{@code consensus.single_ai_confidence_factor} — 單邊 AI 時 consensus 乘以此係數（預設 1.0，不降）</li>
 * </ul>
 */
@Component
public class ConsensusScoringEngine {

    public static final String MODE_FULL_AI     = "FULL_AI";
    public static final String MODE_CLAUDE_ONLY = "CLAUDE_ONLY";
    public static final String MODE_CODEX_ONLY  = "CODEX_ONLY";
    public static final String MODE_AI_MISSING  = "AI_MISSING";

    private final ScoreConfigService config;

    public ConsensusScoringEngine(ScoreConfigService config) {
        this.config = config;
    }

    public record ConsensusInput(
            BigDecimal javaScore,
            BigDecimal claudeScore,
            BigDecimal codexScore
    ) {}

    /**
     * v2.8：含 aiConfidenceMode trace。
     */
    public record ConsensusResult(
            BigDecimal consensusScore,
            BigDecimal disagreementPenalty,
            String aiConfidenceMode
    ) {
        /** v2.7 legacy 2-arg constructor（不帶 mode，預設 FULL_AI）。 */
        public ConsensusResult(BigDecimal consensusScore, BigDecimal disagreementPenalty) {
            this(consensusScore, disagreementPenalty, MODE_FULL_AI);
        }
    }

    public ConsensusResult compute(ConsensusInput input) {
        BigDecimal claude = input.claudeScore();
        BigDecimal codex  = input.codexScore();

        boolean hasClaude = claude != null;
        boolean hasCodex  = codex  != null;

        // v2.8 AI_MISSING：兩邊皆無 → 回 null，呼叫方 fallback 到 weighted
        if (!hasClaude && !hasCodex) {
            return new ConsensusResult(null, BigDecimal.ZERO, MODE_AI_MISSING);
        }

        // 單邊 AI：consensus = 該側分數（原則 A/B：合理 fallback 不壓低）
        if (!hasClaude) {
            return new ConsensusResult(scale(codex), BigDecimal.ZERO, MODE_CODEX_ONLY);
        }
        if (!hasCodex) {
            return new ConsensusResult(scale(claude), BigDecimal.ZERO, MODE_CLAUDE_ONLY);
        }

        // FULL_AI：claude + codex 都有，算一致性與懲罰
        BigDecimal penaltyWeight = config.getDecimal("consensus.penalty_cx", new BigDecimal("0.20"));
        BigDecimal diff = claude.subtract(codex).abs();
        BigDecimal penalty = diff.multiply(penaltyWeight);

        BigDecimal base = claude.min(codex);
        BigDecimal consensus = base.subtract(penalty).max(BigDecimal.ZERO);

        return new ConsensusResult(
                scale(consensus),
                scale(penalty),
                MODE_FULL_AI
        );
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? null : v.setScale(3, RoundingMode.HALF_UP);
    }
}
