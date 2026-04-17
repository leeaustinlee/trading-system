package com.austin.trading.engine;

import com.austin.trading.dto.request.PositionSizingEvaluateRequest;
import com.austin.trading.dto.response.PositionSizingResponse;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class PositionSizingEngine {

    public PositionSizingResponse evaluate(PositionSizingEvaluateRequest request) {
        String marketGrade = normalize(request.marketGrade());
        String valuationMode = normalize(request.valuationMode());

        double marketFactor = switch (marketGrade) {
            case "A" -> 1.0;
            case "B" -> 0.7;
            default -> 0.0;
        };

        double valuationFactor = switch (valuationMode) {
            case "VALUE_LOW" -> 1.0;
            case "VALUE_FAIR" -> 0.85;
            case "VALUE_HIGH" -> 0.6;
            case "VALUE_STORY" -> 0.45;
            default -> 0.5;
        };

        double highRiskFactor = request.nearDayHigh() ? 0.75 : 1.0;
        double multiplier = marketFactor * valuationFactor * highRiskFactor;
        multiplier = Math.max(0.0, Math.min(1.0, multiplier));

        double cappedCapital = Math.min(request.baseCapital(), request.maxSinglePosition());
        double suggestedSize = cappedCapital * request.riskBudgetRatio() * multiplier;

        String rationale = "market=" + marketGrade
                + ", valuation=" + valuationMode
                + ", nearDayHigh=" + request.nearDayHigh()
                + ", multiplier=" + String.format(Locale.ROOT, "%.4f", multiplier);

        return new PositionSizingResponse(multiplier, suggestedSize, rationale);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
