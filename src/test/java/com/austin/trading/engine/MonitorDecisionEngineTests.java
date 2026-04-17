package com.austin.trading.engine;

import com.austin.trading.dto.request.MonitorEvaluateRequest;
import com.austin.trading.dto.response.MonitorDecisionResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitorDecisionEngineTests {

    private final MonitorDecisionEngine engine = new MonitorDecisionEngine();

    @Test
    void shouldTurnOffWhenLockedAndNoPosition() {
        MonitorDecisionResponse result = engine.evaluate(new MonitorEvaluateRequest(
                "B",
                "WATCH",
                "高檔震盪期",
                "WATCH",
                "NONE",
                LocalTime.of(11, 0),
                false,
                true,
                false,
                "LOCKED",
                "MID"
        ));

        assertEquals("OFF", result.monitorMode());
        assertFalse(result.shouldNotify());
    }

    @Test
    void shouldBeActiveWhenGradeAWithCriticalEvent() {
        MonitorDecisionResponse result = engine.evaluate(new MonitorEvaluateRequest(
                "A",
                "ENTER",
                "主升發動期",
                "WATCH",
                "NONE",
                LocalTime.of(9, 30),
                false,
                true,
                true,
                "NONE",
                "EARLY"
        ));

        assertEquals("ACTIVE", result.monitorMode());
        assertTrue(result.shouldNotify());
    }
}
