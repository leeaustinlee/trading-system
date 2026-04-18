package com.austin.trading.engine;

import com.austin.trading.service.ScoreConfigService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 共識評分引擎（v2.0 BC Sniper）。
 *
 * <h3>設計邏輯</h3>
 * <p>在加權平均之外，額外計算「AI 一致性分數」，懲罰分歧大的標的。
 * 最終 final_rank_score = min(ai_weighted_score, consensus_score)，
 * 確保三個 AI 分歧時分數不會虛高。</p>
 *
 * <pre>
 * consensus_base      = min(java, claude, codex)
 *
 * disagreement_penalty =
 *   |java - claude| * weight_jc
 *   + |java - codex|  * weight_jx
 *   + |claude - codex|* weight_cx
 *
 * consensus_score = max(consensus_base - disagreement_penalty, 0)
 * </pre>
 *
 * <p>所有懲罰係數均可透過 score_config 調整：
 * <ul>
 *   <li>{@code consensus.penalty_jc} — java vs claude 懲罰係數（預設 0.25）</li>
 *   <li>{@code consensus.penalty_jx} — java vs codex  懲罰係數（預設 0.20）</li>
 *   <li>{@code consensus.penalty_cx} — claude vs codex 懲罰係數（預設 0.20）</li>
 * </ul>
 * </p>
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
     * 計算共識分與分歧懲罰。
     *
     * <p>若任一分數為 null，只用現有分數計算；
     * 若三個全為 null，回傳 consensusScore=0, penalty=0。</p>
     */
    public ConsensusResult compute(ConsensusInput input) {
        BigDecimal java   = input.javaScore();
        BigDecimal claude = input.claudeScore();
        BigDecimal codex  = input.codexScore();

        // 找出所有非 null 分數中的最小值（consensus_base）
        BigDecimal base = minOf(java, claude, codex);
        if (base == null) {
            return new ConsensusResult(BigDecimal.ZERO, BigDecimal.ZERO);
        }

        // 分歧懲罰係數（可從 config 調整）
        BigDecimal wJC = config.getDecimal("consensus.penalty_jc", new BigDecimal("0.25"));
        BigDecimal wJX = config.getDecimal("consensus.penalty_jx", new BigDecimal("0.20"));
        BigDecimal wCX = config.getDecimal("consensus.penalty_cx", new BigDecimal("0.20"));

        BigDecimal penalty = BigDecimal.ZERO;
        if (java != null && claude != null) {
            penalty = penalty.add(java.subtract(claude).abs().multiply(wJC));
        }
        if (java != null && codex != null) {
            penalty = penalty.add(java.subtract(codex).abs().multiply(wJX));
        }
        if (claude != null && codex != null) {
            penalty = penalty.add(claude.subtract(codex).abs().multiply(wCX));
        }

        BigDecimal rawConsensus = base.subtract(penalty);
        BigDecimal consensus = rawConsensus.max(BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP);

        return new ConsensusResult(consensus, penalty.setScale(3, RoundingMode.HALF_UP));
    }

    // ── 私有工具 ──────────────────────────────────────────────────────────────

    /** 回傳所有非 null 值中的最小值，若全為 null 則回傳 null */
    private BigDecimal minOf(BigDecimal... values) {
        BigDecimal min = null;
        for (BigDecimal v : values) {
            if (v == null) continue;
            min = (min == null) ? v : v.min(min);
        }
        return min;
    }
}
