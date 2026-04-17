package com.austin.trading.engine;

import com.austin.trading.engine.ReviewScoringEngine.ReviewRequest;
import com.austin.trading.engine.ReviewScoringEngine.ReviewResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReviewScoringEngineTests {

    private final ReviewScoringEngine engine = new ReviewScoringEngine();

    @Test
    void shouldGivePerfectScoreWhenAllCompliant() {
        ReviewResult result = engine.evaluate(new ReviewRequest(
                "A", "A", "ENTER", false, true, false, false
        ));

        assertEquals(100, result.score());
        assertTrue(result.violations().isEmpty());
        assertTrue(result.compliance().startsWith("YES"));
    }

    @Test
    void shouldDeductForWrongMarketGrade() {
        ReviewResult result = engine.evaluate(new ReviewRequest(
                "A", "C", "WATCH", false, true, false, false
        ));

        assertFalse(result.violations().isEmpty());
        assertTrue(result.score() < 100);
        assertTrue(result.violations().stream().anyMatch(v -> v.contains("盤型判斷錯誤")));
    }

    @Test
    void shouldDeductForEnterInGradeC() {
        ReviewResult result = engine.evaluate(new ReviewRequest(
                "C", "C", "ENTER", false, true, false, false
        ));

        assertTrue(result.score() <= 70);
        assertTrue(result.violations().stream().anyMatch(v -> v.contains("C 級")));
    }

    @Test
    void shouldDeductForMissingStopLoss() {
        ReviewResult result = engine.evaluate(new ReviewRequest(
                "A", "A", "ENTER", true, false, false, false
        ));

        assertTrue(result.violations().stream().anyMatch(v -> v.contains("未執行停損")));
        assertTrue(result.score() < 90);
    }

    @Test
    void shouldDeductForChasingHighEntry() {
        ReviewResult result = engine.evaluate(new ReviewRequest(
                "A", "A", "ENTER", false, true, true, false
        ));

        assertTrue(result.violations().stream().anyMatch(v -> v.contains("追高")));
        assertTrue(result.score() < 90);
    }

    @Test
    void shouldNotGoBelowZero() {
        ReviewResult result = engine.evaluate(new ReviewRequest(
                "A", "C", "ENTER", true, false, true, true
        ));

        assertTrue(result.score() >= 0);
        assertFalse(result.compliance().startsWith("YES"));
    }
}
