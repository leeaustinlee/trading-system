package com.austin.trading.engine;

import com.austin.trading.service.ScoreConfigService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 共識評分引擎（v2.7 MVP Refactor — 去 Java 化）。
 *
 * <h3>設計變更（v2.7）</h3>
 * <p>原 v2.0 算法把 Java 結構分同時當 base 下限 + 分歧懲罰來源，
 * 讓 AI 雙高（Claude/Codex 高）但 Java 結構中等的強股被雙重打壓。
 * v2.7 改為只看 Claude/Codex 之間的一致性：</p>
 *
 * <pre>
 * // v2.7
 * base    = min(claude, codex)
 * penalty = |claude - codex| * consensus.penalty_cx
 * consensus_score = max(base - penalty, 0)
 *
 * // 若 claude 或 codex 其中一方為 null → 回傳 null，由呼叫方 fallback 到 weighted_score
 * </pre>
 *
 * <p>Java 結構分繼續在 {@code WeightedScoringEngine} 扮演 40% 權重角色，
 * 不再介入共識懲罰。</p>
 *
 * <h3>Config</h3>
 * <ul>
 *   <li>{@code consensus.penalty_cx} — Claude 與 Codex 分歧懲罰係數（預設 0.20）</li>
 *   <li>{@code consensus.penalty_jc} — <b>廢棄</b>（v2.7 不再讀取）</li>
 *   <li>{@code consensus.penalty_jx} — <b>廢棄</b>（v2.7 不再讀取）</li>
 * </ul>
 */
@Component
public class ConsensusScoringEngine {

    private final ScoreConfigService config;

    public ConsensusScoringEngine(ScoreConfigService config) {
        this.config = config;
    }

    public record ConsensusInput(
            BigDecimal javaScore,
            BigDecimal claudeScore,
            BigDecimal codexScore
    ) {}

    public record ConsensusResult(
            BigDecimal consensusScore,
            BigDecimal disagreementPenalty
    ) {}

    /**
     * 計算 AI 共識分與分歧懲罰（v2.7）。
     *
     * <p>算法：
     * <ul>
     *   <li>若 {@code claudeScore} 與 {@code codexScore} 都有值 →
     *       base=min(claude,codex)，penalty=|diff|×{@code consensus.penalty_cx}，
     *       consensus=max(base−penalty, 0)</li>
     *   <li>若其中一方為 null → 無法判斷 AI 共識，
     *       回傳 {@code consensusScore=null}（呼叫方應 fallback 到 weighted_score）</li>
     *   <li>若兩方都為 null → 同上，回傳 null</li>
     * </ul>
     *
     * <p>{@code javaScore} 僅作為輸入欄位保留（向下相容 ConsensusInput 結構），
     * v2.7 後不再用於計算。</p>
     */
    public ConsensusResult compute(ConsensusInput input) {
        BigDecimal claude = input.claudeScore();
        BigDecimal codex  = input.codexScore();

        // v2.7: 只看 Claude/Codex 之間的一致性
        // 若任一方為 null → AI 層無共識可言，回 null 讓下游 fallback
        if (claude == null || codex == null) {
            return new ConsensusResult(null, BigDecimal.ZERO);
        }

        BigDecimal penaltyWeight = config.getDecimal("consensus.penalty_cx", new BigDecimal("0.20"));
        BigDecimal diff = claude.subtract(codex).abs();
        BigDecimal penalty = diff.multiply(penaltyWeight);

        BigDecimal base = claude.min(codex);
        BigDecimal consensus = base.subtract(penalty)
                .max(BigDecimal.ZERO)
                .setScale(3, RoundingMode.HALF_UP);

        return new ConsensusResult(
                consensus,
                penalty.setScale(3, RoundingMode.HALF_UP)
        );
    }
}
