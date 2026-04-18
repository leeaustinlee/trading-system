package com.austin.trading.engine;

import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ConsensusScoringEngineTests {

    private ConsensusScoringEngine engine;

    @BeforeEach
    void setUp() {
        ScoreConfigService config = mock(ScoreConfigService.class);
        when(config.getDecimal(eq("consensus.penalty_jc"), any())).thenReturn(new BigDecimal("0.25"));
        when(config.getDecimal(eq("consensus.penalty_jx"), any())).thenReturn(new BigDecimal("0.20"));
        when(config.getDecimal(eq("consensus.penalty_cx"), any())).thenReturn(new BigDecimal("0.20"));
        engine = new ConsensusScoringEngine(config);
    }

    @Test
    void allNullShouldReturnZero() {
        ConsensusScoringEngine.ConsensusResult result = engine.compute(
                new ConsensusScoringEngine.ConsensusInput(null, null, null));
        assertEquals(0, result.consensusScore().compareTo(BigDecimal.ZERO));
        assertEquals(0, result.disagreementPenalty().compareTo(BigDecimal.ZERO));
    }

    @Test
    void perfectAgreementShouldHaveNoPenalty() {
        // 三分相同 → penalty=0, consensus=score
        ConsensusScoringEngine.ConsensusResult result = engine.compute(
                new ConsensusScoringEngine.ConsensusInput(
                        new BigDecimal("8.0"),
                        new BigDecimal("8.0"),
                        new BigDecimal("8.0")));
        assertEquals(0, result.disagreementPenalty().compareTo(BigDecimal.ZERO));
        assertEquals(0, result.consensusScore().compareTo(new BigDecimal("8.000")));
    }

    @Test
    void moderateDivergenceShouldReduceConsensus() {
        // java=9, claude=7, codex=8
        // base = min(9,7,8) = 7
        // penalty = |9-7|*0.25 + |9-8|*0.20 + |7-8|*0.20
        //         = 2*0.25 + 1*0.20 + 1*0.20 = 0.50 + 0.20 + 0.20 = 0.90
        // consensus = max(7 - 0.90, 0) = 6.100
        ConsensusScoringEngine.ConsensusResult result = engine.compute(
                new ConsensusScoringEngine.ConsensusInput(
                        new BigDecimal("9.0"),
                        new BigDecimal("7.0"),
                        new BigDecimal("8.0")));
        assertEquals(0, result.disagreementPenalty().compareTo(new BigDecimal("0.900")),
                "penalty 應為 0.900，實際：" + result.disagreementPenalty());
        assertEquals(0, result.consensusScore().compareTo(new BigDecimal("6.100")),
                "consensus 應為 6.100，實際：" + result.consensusScore());
    }

    @Test
    void largeDivergenceShouldClampToZero() {
        // java=10, claude=0, codex=5
        // base = min = 0
        // penalty = |10-0|*0.25 + |10-5|*0.20 + |0-5|*0.20 = 2.5+1.0+1.0 = 4.5
        // consensus = max(0 - 4.5, 0) = 0
        ConsensusScoringEngine.ConsensusResult result = engine.compute(
                new ConsensusScoringEngine.ConsensusInput(
                        new BigDecimal("10.0"),
                        new BigDecimal("0.0"),
                        new BigDecimal("5.0")));
        assertEquals(0, result.consensusScore().compareTo(BigDecimal.ZERO),
                "極端分歧下 consensus 應為 0，實際：" + result.consensusScore());
    }

    @Test
    void missingCodexShouldOnlyPenalizeJavaClaude() {
        // java=9, claude=7, codex=null
        // base = min(9,7) = 7
        // penalty = |9-7|*0.25 = 0.50（僅 JC pair）
        // consensus = max(7 - 0.50, 0) = 6.500
        ConsensusScoringEngine.ConsensusResult result = engine.compute(
                new ConsensusScoringEngine.ConsensusInput(
                        new BigDecimal("9.0"),
                        new BigDecimal("7.0"),
                        null));
        assertEquals(0, result.disagreementPenalty().compareTo(new BigDecimal("0.500")),
                "penalty 應為 0.500，實際：" + result.disagreementPenalty());
        assertEquals(0, result.consensusScore().compareTo(new BigDecimal("6.500")),
                "consensus 應為 6.500，實際：" + result.consensusScore());
    }

    @Test
    void singleScoreShouldReturnThatScoreWithZeroPenalty() {
        // 只有 javaScore，其他 null
        ConsensusScoringEngine.ConsensusResult result = engine.compute(
                new ConsensusScoringEngine.ConsensusInput(
                        new BigDecimal("7.5"),
                        null,
                        null));
        assertEquals(0, result.disagreementPenalty().compareTo(BigDecimal.ZERO));
        assertEquals(0, result.consensusScore().compareTo(new BigDecimal("7.500")));
    }
}
