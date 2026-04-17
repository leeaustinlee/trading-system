package com.austin.trading.engine;

import com.austin.trading.dto.request.MarketGateEvaluateRequest;
import com.austin.trading.dto.response.MarketGateDecisionResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketGateEngineTests {

    private final MarketGateEngine engine = new MarketGateEngine();

    @Test
    void shouldReturnGradeAWhenSignalsStrong() {
        MarketGateDecisionResponse result = engine.evaluate(new MarketGateEvaluateRequest(
                true,
                true,
                true,
                false,
                true,
                false,
                LocalTime.of(9, 20)
        ));

        assertEquals("A", result.marketGrade());
        assertEquals("ENTER", result.decision());
        assertTrue(result.allowTrade());
    }

    @Test
    void shouldReturnGradeCWhenBlowoffTopSignal() {
        MarketGateDecisionResponse result = engine.evaluate(new MarketGateEvaluateRequest(
                true,
                false,
                false,
                true,
                false,
                true,
                LocalTime.of(10, 40)
        ));

        assertEquals("C", result.marketGrade());
        assertEquals("REST", result.decision());
        assertFalse(result.allowTrade());
    }
}
