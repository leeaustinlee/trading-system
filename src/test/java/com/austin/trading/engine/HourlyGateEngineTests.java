package com.austin.trading.engine;

import com.austin.trading.dto.request.HourlyGateEvaluateRequest;
import com.austin.trading.dto.response.HourlyGateDecisionResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HourlyGateEngineTests {

    private final HourlyGateEngine engine = new HourlyGateEngine();

    @Test
    void shouldForceOffHardInLateSessionWithoutPosition() {
        HourlyGateDecisionResponse result = engine.evaluate(new HourlyGateEvaluateRequest(
                "B",
                "REST",
                "B",
                "WATCH",
                "ON",
                "NONE",
                "NONE",
                LocalTime.of(10, 45),
                false,
                true,
                false
        ));

        assertEquals("OFF_HARD", result.hourlyGate());
        assertEquals("LOCKED", result.decisionLock());
        assertFalse(result.shouldRun5mMonitor());
    }

    @Test
    void shouldRun5mWhenGradeAAndHasCandidate() {
        HourlyGateDecisionResponse result = engine.evaluate(new HourlyGateEvaluateRequest(
                "A",
                "ENTER",
                "B",
                "WATCH",
                "OFF_SOFT",
                "LOCKED",
                "MARKET_DOWNGRADE",
                LocalTime.of(9, 35),
                false,
                true,
                true
        ));

        assertEquals("ON", result.hourlyGate());
        assertTrue(result.shouldRun5mMonitor());
        assertTrue(result.shouldNotify());
    }
}
