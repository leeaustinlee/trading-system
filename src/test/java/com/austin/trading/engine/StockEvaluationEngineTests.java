package com.austin.trading.engine;

import com.austin.trading.dto.request.StockEvaluateRequest;
import com.austin.trading.dto.response.StockEvaluateResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StockEvaluationEngineTests {

    private final StockEvaluationEngine engine =
            new StockEvaluationEngine(new StopLossTakeProfitEngine());

    @Test
    void shouldComputeStopLossAndTakeProfitFromEntry() {
        StockEvaluateResult result = engine.evaluate(req("2330", "A", "BREAKOUT", 100.0, 5.0, 8.0, 12.0, false));

        assertTrue(result.stopLossPrice() < 100.0, "stopLoss should be below entry");
        assertTrue(result.takeProfit1()   > 100.0, "tp1 should be above entry");
        assertTrue(result.takeProfit2()   > result.takeProfit1(), "tp2 > tp1");
    }

    @Test
    void shouldIncludeInPlanWhenAllConditionsMet() {
        // sl=3% → stopLoss=97, tp1=10% → tp1=110, RR=(110-100)/(100-97)=3.33 >= 1.8
        StockEvaluateResult result = engine.evaluate(req("2303", "A", "PULLBACK", 100.0, 3.0, 10.0, 15.0, false));

        assertTrue(result.includeInFinalPlan(), "should include when RR OK and grade A");
        assertNull(result.rejectReason(), "rejectReason should be null");
    }

    @Test
    void shouldExcludeWhenMarketGradeBAndRrBelow2() {
        // sl=5% → stopLoss=95, tp1=9% → tp1=109, RR=(109-100)/(100-95)=1.8 < 2.0 → exclude in B
        StockEvaluateResult result = engine.evaluate(
                new StockEvaluateRequest("2454", "B", "BREAKOUT",
                        100.0, 5.0, 9.0, 13.0, false, false, true, true, true, 30));

        assertFalse(result.includeInFinalPlan());
        assertNotNull(result.rejectReason());
        assertTrue(result.rejectReason().contains("風報比"));
    }

    @Test
    void shouldExcludeWhenBelowOpen() {
        StockEvaluateRequest req = new StockEvaluateRequest(
                "2317", "A", "BREAKOUT", 100.0, 5.0, 8.0, 12.0, false, false,
                false,  // aboveOpen = false
                true, true, 20);

        StockEvaluateResult result = engine.evaluate(req);
        assertFalse(result.includeInFinalPlan());
        assertEquals("跌破開盤價", result.rejectReason());
    }

    @Test
    void shouldClassifyValuationCorrectly() {
        assertEquals("VALUE_LOW",   engine.evaluate(req("X", "A", "BREAKOUT", 100.0, 5.0, 8.0, 12.0, false,  0)).valuationMode());
        assertEquals("VALUE_LOW",   engine.evaluate(req("X", "A", "BREAKOUT", 100.0, 5.0, 8.0, 12.0, false, 30)).valuationMode());
        assertEquals("VALUE_FAIR",  engine.evaluate(req("X", "A", "BREAKOUT", 100.0, 5.0, 8.0, 12.0, false, 31)).valuationMode());
        assertEquals("VALUE_FAIR",  engine.evaluate(req("X", "A", "BREAKOUT", 100.0, 5.0, 8.0, 12.0, false, 55)).valuationMode());
        assertEquals("VALUE_HIGH",  engine.evaluate(req("X", "A", "BREAKOUT", 100.0, 5.0, 8.0, 12.0, false, 56)).valuationMode());
        assertEquals("VALUE_STORY", engine.evaluate(req("X", "A", "BREAKOUT", 100.0, 5.0, 8.0, 12.0, false, 76)).valuationMode());
    }

    @Test
    void shouldExcludeValueStoryInNonAMarket() {
        StockEvaluateRequest req = new StockEvaluateRequest(
                "X", "B", "BREAKOUT", 100.0, 5.0, 8.0, 12.0, false, false, true, true, true, 80);

        StockEvaluateResult result = engine.evaluate(req);
        assertFalse(result.includeInFinalPlan());
        assertTrue(result.rejectReason().contains("估值"));
    }

    @Test
    void shouldExcludeNonMainStream() {
        StockEvaluateRequest req = new StockEvaluateRequest(
                "X", "A", "BREAKOUT", 100.0, 5.0, 8.0, 12.0, false, false, true, true,
                false,  // mainStream = false
                20);

        StockEvaluateResult result = engine.evaluate(req);
        assertFalse(result.includeInFinalPlan());
        assertEquals("非主流族群", result.rejectReason());
    }

    // ── 輔助方法 ──────────────────────────────────────────────────────────────

    private StockEvaluateRequest req(String symbol, String grade, String entryType,
                                     double entry, double slPct, double tp1Pct, double tp2Pct,
                                     boolean volatile_, int valuationScore) {
        return new StockEvaluateRequest(symbol, grade, entryType,
                entry, slPct, tp1Pct, tp2Pct, volatile_, false, true, true, true, valuationScore);
    }

    private StockEvaluateRequest req(String symbol, String grade, String entryType,
                                     double entry, double slPct, double tp1Pct, double tp2Pct,
                                     boolean volatile_) {
        return req(symbol, grade, entryType, entry, slPct, tp1Pct, tp2Pct, volatile_, 20);
    }
}
