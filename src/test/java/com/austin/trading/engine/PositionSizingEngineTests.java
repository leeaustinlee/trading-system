package com.austin.trading.engine;

import com.austin.trading.dto.request.PositionSizingEvaluateRequest;
import com.austin.trading.dto.response.PositionSizingResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionSizingEngineTests {

    private final PositionSizingEngine engine = new PositionSizingEngine();

    @Test
    void shouldGiveHigherSizeInGradeAValueLow() {
        PositionSizingResponse result = engine.evaluate(new PositionSizingEvaluateRequest(
                "A", "VALUE_LOW", 100000d, 50000d, 1.0d, false
        ));

        assertEquals(1.0d, result.positionSizeMultiplier(), 1e-9);
        assertEquals(50000d, result.suggestedPositionSize(), 1e-9);
    }

    @Test
    void shouldReduceSizeInGradeBAndNearHigh() {
        PositionSizingResponse result = engine.evaluate(new PositionSizingEvaluateRequest(
                "B", "VALUE_STORY", 100000d, 50000d, 1.0d, true
        ));

        assertTrue(result.positionSizeMultiplier() < 0.3d);
        assertTrue(result.suggestedPositionSize() < 15000d);
    }
}
