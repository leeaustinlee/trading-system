package com.austin.trading.engine;

import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import com.austin.trading.dto.request.FinalDecisionEvaluateRequest;
import com.austin.trading.dto.response.FinalDecisionResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FinalDecisionEngineTests {

    private final FinalDecisionEngine engine = new FinalDecisionEngine();

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
    void shouldSelectTopTwoInGradeA() {
        FinalDecisionResponse result = engine.evaluate(new FinalDecisionEvaluateRequest(
                "A",
                "NONE",
                "EARLY",
                false,
                List.of(
                        candidate("2330", 2.5, "VALUE_FAIR"),
                        candidate("2303", 2.1, "VALUE_LOW"),
                        candidate("2454", 1.9, "VALUE_LOW")
                )
        ));

        assertEquals("ENTER", result.decision());
        assertEquals(2, result.selectedStocks().size());
        assertEquals("2330", result.selectedStocks().get(0).stockCode());
    }

    @Test
    void shouldOnlyPickOneInGradeB() {
        FinalDecisionResponse result = engine.evaluate(new FinalDecisionEvaluateRequest(
                "B",
                "NONE",
                "MID",
                false,
                List.of(
                        candidate("2330", 2.2, "VALUE_FAIR"),
                        candidate("2303", 2.1, "VALUE_LOW")
                )
        ));

        assertEquals("ENTER", result.decision());
        assertEquals(1, result.selectedStocks().size());
    }

    private FinalDecisionCandidateRequest candidate(String code, double rr, String valuationMode) {
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
                null, null          // finalRankScore, isVetoed
        );
    }
}
