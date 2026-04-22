package com.austin.trading.engine;

import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * v2.7 ConsensusScoringEngine tests — 去 Java 化：只算 Claude/Codex 共識。
 */
class ConsensusScoringEngineTests {

    private ConsensusScoringEngine engine;

    @BeforeEach
    void setUp() {
        ScoreConfigService config = mock(ScoreConfigService.class);
        when(config.getDecimal(eq("consensus.penalty_cx"), any())).thenReturn(new BigDecimal("0.20"));
        engine = new ConsensusScoringEngine(config);
    }

    // ── v2.7 核心：Java 不再影響共識 ───────────────────────────────────────

    @Test
    void javaNotInConsensus_strongStockNotPunished() {
        // v2.6 之前：java=5.2, claude=9.0, codex=8.0
        //   base=min(5.2,9,8)=5.2
        //   penalty = |5.2-9|*0.25 + |5.2-8|*0.20 + |9-8|*0.20 = 0.95+0.56+0.2 = 1.71
        //   consensus = 5.2-1.71 = 3.49（強股被錯殺）
        //
        // v2.7：只看 Claude/Codex
        //   base = min(9, 8) = 8
        //   penalty = |9-8| * 0.20 = 0.20
        //   consensus = 8 - 0.20 = 7.80（強股合理分數）
        ConsensusScoringEngine.ConsensusResult result = engine.compute(
                new ConsensusScoringEngine.ConsensusInput(
                        new BigDecimal("5.2"),   // java
                        new BigDecimal("9.0"),   // claude
                        new BigDecimal("8.0"))); // codex
        assertThat(result.consensusScore()).isEqualByComparingTo(new BigDecimal("7.800"));
        assertThat(result.disagreementPenalty()).isEqualByComparingTo(new BigDecimal("0.200"));
    }

    @Test
    void javaValueIgnored_sameConsensusWhenJavaVaries() {
        // Java 從 5.2 改成 9.8，只要 Claude/Codex 不變，consensus 相同
        ConsensusScoringEngine.ConsensusResult r1 = engine.compute(
                new ConsensusScoringEngine.ConsensusInput(new BigDecimal("5.2"),
                        new BigDecimal("8.0"), new BigDecimal("7.0")));
        ConsensusScoringEngine.ConsensusResult r2 = engine.compute(
                new ConsensusScoringEngine.ConsensusInput(new BigDecimal("9.8"),
                        new BigDecimal("8.0"), new BigDecimal("7.0")));
        assertThat(r1.consensusScore()).isEqualByComparingTo(r2.consensusScore());
        assertThat(r1.disagreementPenalty()).isEqualByComparingTo(r2.disagreementPenalty());
    }

    // ── Claude / Codex 共識計算 ──────────────────────────────────────────

    @Test
    void perfectAgreement_noPenalty() {
        // claude = codex → penalty=0, consensus=claude
        ConsensusScoringEngine.ConsensusResult result = engine.compute(
                new ConsensusScoringEngine.ConsensusInput(
                        new BigDecimal("5.0"),
                        new BigDecimal("8.0"),
                        new BigDecimal("8.0")));
        assertThat(result.disagreementPenalty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.consensusScore()).isEqualByComparingTo(new BigDecimal("8.000"));
    }

    @Test
    void moderateDivergence_reducedConsensus() {
        // claude=7, codex=8 → base=7, penalty=|7-8|*0.2=0.2, consensus=6.8
        ConsensusScoringEngine.ConsensusResult result = engine.compute(
                new ConsensusScoringEngine.ConsensusInput(
                        null,
                        new BigDecimal("7.0"),
                        new BigDecimal("8.0")));
        assertThat(result.consensusScore()).isEqualByComparingTo(new BigDecimal("6.800"));
        assertThat(result.disagreementPenalty()).isEqualByComparingTo(new BigDecimal("0.200"));
    }

    @Test
    void extremeDivergence_clampToZero() {
        // claude=1, codex=9 → base=1, penalty=|1-9|*0.2=1.6, consensus = max(1-1.6, 0) = 0
        ConsensusScoringEngine.ConsensusResult result = engine.compute(
                new ConsensusScoringEngine.ConsensusInput(
                        null,
                        new BigDecimal("1.0"),
                        new BigDecimal("9.0")));
        assertThat(result.consensusScore()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── v2.8 P0.9：單邊 AI fallback（原 v2.7 回 null，現改用該分數當 consensus） ──

    @Test
    void claudeOnly_consensusEqualsClaude() {
        // codex null → CLAUDE_ONLY：consensus = claude，不再壓成 null
        ConsensusScoringEngine.ConsensusResult result = engine.compute(
                new ConsensusScoringEngine.ConsensusInput(
                        new BigDecimal("5.2"),   // java
                        new BigDecimal("8.0"),   // claude
                        null));                  // codex
        assertThat(result.consensusScore()).isEqualByComparingTo(new BigDecimal("8.000"));
        assertThat(result.aiConfidenceMode())
                .isEqualTo(ConsensusScoringEngine.MODE_CLAUDE_ONLY);
        assertThat(result.disagreementPenalty()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void codexOnly_consensusEqualsCodex() {
        // claude null → CODEX_ONLY：consensus = codex
        ConsensusScoringEngine.ConsensusResult result = engine.compute(
                new ConsensusScoringEngine.ConsensusInput(
                        new BigDecimal("5.2"),
                        null,
                        new BigDecimal("7.5")));
        assertThat(result.consensusScore()).isEqualByComparingTo(new BigDecimal("7.500"));
        assertThat(result.aiConfidenceMode())
                .isEqualTo(ConsensusScoringEngine.MODE_CODEX_ONLY);
    }

    @Test
    void bothAiNull_consensusNull_modeAiMissing() {
        // 兩邊皆 null → AI_MISSING，consensus 仍 null
        ConsensusScoringEngine.ConsensusResult result = engine.compute(
                new ConsensusScoringEngine.ConsensusInput(
                        new BigDecimal("8.0"),
                        null, null));
        assertThat(result.consensusScore()).isNull();
        assertThat(result.aiConfidenceMode())
                .isEqualTo(ConsensusScoringEngine.MODE_AI_MISSING);
        assertThat(result.disagreementPenalty()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void fullAi_modeIsFullAi() {
        ConsensusScoringEngine.ConsensusResult result = engine.compute(
                new ConsensusScoringEngine.ConsensusInput(
                        new BigDecimal("5.2"),
                        new BigDecimal("8.0"),
                        new BigDecimal("6.0")));
        assertThat(result.aiConfidenceMode())
                .isEqualTo(ConsensusScoringEngine.MODE_FULL_AI);
    }
}
