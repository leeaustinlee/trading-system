package com.austin.trading.engine;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 判斷是否存在「追高進場」：
 * entryPrice >= dayHigh * (1 - threshold)。
 */
@Component
public class ChasedHighEntryEngine {

    public boolean hasChasedEntry(List<ChasedEntryInput> inputs, double threshold) {
        if (inputs == null || inputs.isEmpty()) {
            return false;
        }
        double safeThreshold = Math.max(0.0, threshold);
        for (ChasedEntryInput input : inputs) {
            if (input == null) continue;
            if (isChased(input.entryPrice(), input.dayHigh(), safeThreshold)) {
                return true;
            }
        }
        return false;
    }

    boolean isChased(Double entryPrice, Double dayHigh, double threshold) {
        if (entryPrice == null || dayHigh == null || dayHigh <= 0 || entryPrice <= 0) {
            return false;
        }
        return entryPrice >= dayHigh * (1.0 - threshold);
    }

    public record ChasedEntryInput(
            String symbol,
            Double entryPrice,
            Double dayHigh
    ) {
    }
}
