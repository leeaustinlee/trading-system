package com.austin.trading.engine;

import com.austin.trading.engine.ChasedHighEntryEngine.ChasedEntryInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChasedHighEntryEngineTests {

    private final ChasedHighEntryEngine engine = new ChasedHighEntryEngine();

    @Test
    void shouldDetectChasedEntryWhenEntryIsWithinPointFivePercentOfDayHigh() {
        boolean chased = engine.hasChasedEntry(
                List.of(new ChasedEntryInput("2330", 99.60, 100.00)),
                0.005
        );

        assertTrue(chased);
    }

    @Test
    void shouldReturnFalseWhenEntryIsBelowThreshold() {
        boolean chased = engine.hasChasedEntry(
                List.of(new ChasedEntryInput("2330", 98.90, 100.00)),
                0.005
        );

        assertFalse(chased);
    }

    @Test
    void shouldReturnFalseWhenDataIsInsufficient() {
        boolean chased = engine.hasChasedEntry(
                List.of(
                        new ChasedEntryInput("2330", null, 100.0),
                        new ChasedEntryInput("2317", 100.0, null),
                        new ChasedEntryInput("2454", -1.0, 100.0)
                ),
                0.005
        );

        assertFalse(chased);
    }
}
