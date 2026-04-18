package com.austin.trading.engine;

import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import com.austin.trading.dto.request.FinalDecisionEvaluateRequest;
import com.austin.trading.dto.response.FinalDecisionResponse;
import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FinalDecisionEngineTests {

    private FinalDecisionEngine engine;
    private ScoreConfigService config;

    @BeforeEach
    void setUp() {
        config = mock(ScoreConfigService.class);
        when(config.getDecimal(eq("scoring.grade_ap_min"), any())).thenReturn(new BigDecimal("8.8"));
        when(config.getDecimal(eq("scoring.grade_a_min"),  any())).thenReturn(new BigDecimal("8.2"));
        when(config.getDecimal(eq("scoring.grade_b_min"),  any())).thenReturn(new BigDecimal("7.4"));
        when(config.getDecimal(eq("scoring.rr_min_ap"),    any())).thenReturn(new BigDecimal("2.5"));
        when(config.getString(any(), any())).thenReturn("A");
        engine = new FinalDecisionEngine(config);
    }

    @Test
    void shouldRestWhenMarketGradeIsC() {
        FinalDecisionResponse result = engine.evaluate(new FinalDecisionEvaluateRequest(
                "C",
                "NONE",
                "EARLY",
                false,
                List.of()
        ));

        assertEquals("REST", result.decision());
    }

    @Test
    void shouldEnterTwoWhenTwoApCandidates() {
        // 兩檔均 finalRankScore >= 8.8，RR >= 2.5 → ENTER 2
        FinalDecisionResponse result = engine.evaluate(new FinalDecisionEvaluateRequest(
                "A",
                "NONE",
                "EARLY",
                false,
                List.of(
                        candidate("2330", 2.5, "VALUE_FAIR", new BigDecimal("9.2")),
                        candidate("2303", 2.6, "VALUE_LOW",  new BigDecimal("8.9"))
                )
        ));

        assertEquals("ENTER", result.decision());
        assertEquals(2, result.selectedStocks().size());
        assertEquals("2330", result.selectedStocks().get(0).stockCode());
    }

    @Test
    void shouldRestWhenNoApCandidates() {
        // finalRankScore 未達 8.8 → REST
        FinalDecisionResponse result = engine.evaluate(new FinalDecisionEvaluateRequest(
                "A",
                "NONE",
                "EARLY",
                false,
                List.of(
                        candidate("2330", 2.5, "VALUE_FAIR", new BigDecimal("8.5")),
                        candidate("2303", 2.1, "VALUE_LOW",  new BigDecimal("7.9"))
                )
        ));

        assertEquals("REST", result.decision());
    }

    @Test
    void shouldEnterOneWhenOneApCandidate() {
        // 一檔 A+，一檔未達 → ENTER 1
        FinalDecisionResponse result = engine.evaluate(new FinalDecisionEvaluateRequest(
                "A",
                "NONE",
                "EARLY",
                false,
                List.of(
                        candidate("2330", 2.5, "VALUE_FAIR", new BigDecimal("9.0")),
                        candidate("2303", 2.5, "VALUE_LOW",  new BigDecimal("8.0"))
                )
        ));

        assertEquals("ENTER", result.decision());
        assertEquals(1, result.selectedStocks().size());
        assertEquals("2330", result.selectedStocks().get(0).stockCode());
    }

    private FinalDecisionCandidateRequest candidate(String code, double rr, String valuationMode, BigDecimal finalRankScore) {
        return new FinalDecisionCandidateRequest(
                code,
                "TEST-" + code,
                valuationMode,
                "BREAKOUT",
                rr,
                true,
                true,
                false,
                false,
                false,
                false,
                true,
                "ok",
                "100-102",
                98.0,
                108.0,
                115.0,
                null, null, null,   // javaStructureScore, claudeScore, codexScore
                finalRankScore, false, // finalRankScore, isVetoed
                null, null,         // baseScore, hasTheme
                null, null,         // themeRank, finalThemeScore
                null, null,         // consensusScore, disagreementPenalty
                null, null, null,   // volumeSpike, priceNotBreakHigh, entryTooExtended
                true                // entryTriggered
        );
    }
}
