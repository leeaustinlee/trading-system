package com.austin.trading.engine;

import com.austin.trading.dto.request.StopLossTakeProfitEvaluateRequest;
import com.austin.trading.dto.response.StopLossTakeProfitResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StopLossTakeProfitEngineTests {

    private final StopLossTakeProfitEngine engine = new StopLossTakeProfitEngine();

    @Test
    void shouldCalculatePricesFromEntry() {
        StopLossTakeProfitResponse result = engine.evaluate(new StopLossTakeProfitEvaluateRequest(
                100.0, 5.0, 8.0, 12.0, false
        ));

        assertTrue(result.stopLossPrice() < 100.0);
        assertTrue(result.takeProfit1() > 100.0);
        assertTrue(result.takeProfit2() > result.takeProfit1());
    }

    @Test
    void shouldWidenTargetsForVolatileStock() {
        StopLossTakeProfitResponse normal = engine.evaluate(new StopLossTakeProfitEvaluateRequest(
                100.0, 5.0, 8.0, 12.0, false
        ));
        StopLossTakeProfitResponse volatileResult = engine.evaluate(new StopLossTakeProfitEvaluateRequest(
                100.0, 5.0, 8.0, 12.0, true
        ));

        assertTrue(volatileResult.stopLossPrice() < normal.stopLossPrice());
        assertTrue(volatileResult.takeProfit2() > normal.takeProfit2());
    }
}
