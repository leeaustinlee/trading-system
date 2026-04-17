package com.austin.trading.engine;

import com.austin.trading.dto.request.StopLossTakeProfitEvaluateRequest;
import com.austin.trading.dto.response.StopLossTakeProfitResponse;
import org.springframework.stereotype.Component;

@Component
public class StopLossTakeProfitEngine {

    public StopLossTakeProfitResponse evaluate(StopLossTakeProfitEvaluateRequest request) {
        double entry = request.entryPrice();
        double stopLossPct = request.stopLossPercent();
        double tp1Pct = request.takeProfit1Percent();
        double tp2Pct = request.takeProfit2Percent();

        if (request.volatileStock()) {
            stopLossPct = Math.min(stopLossPct + 1.0, 8.0);
            tp1Pct = tp1Pct + 1.0;
            tp2Pct = tp2Pct + 2.0;
        }

        double stopLossPrice = round(entry * (1.0 - stopLossPct / 100.0));
        double takeProfit1 = round(entry * (1.0 + tp1Pct / 100.0));
        double takeProfit2 = round(entry * (1.0 + tp2Pct / 100.0));

        String stopLossStyle = request.volatileStock() ? "VOLATILITY_BUFFER" : "FIXED_PERCENT";
        String takeProfitStyle = request.volatileStock() ? "WIDE_TARGET" : "LADDER_TARGET";
        String rationale = "entry=" + entry + ", stopLossPct=" + stopLossPct + ", tp1Pct=" + tp1Pct + ", tp2Pct=" + tp2Pct;

        return new StopLossTakeProfitResponse(
                stopLossPrice,
                takeProfit1,
                takeProfit2,
                stopLossStyle,
                takeProfitStyle,
                rationale
        );
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
