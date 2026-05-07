package com.austin.trading.engine.tuning;

import com.austin.trading.domain.enums.TuningConfidence;

import java.math.BigDecimal;

public class TuningConfidenceCalculator {
    public TuningConfidence calculate(int sampleSize, BigDecimal signalStrengthPct) {
        if (sampleSize < TuningStats.MIN_SAMPLE) return TuningConfidence.INSUFFICIENT_DATA;
        BigDecimal strength = signalStrengthPct == null ? BigDecimal.ZERO : signalStrengthPct.abs();
        if (sampleSize >= 40 && strength.compareTo(new BigDecimal("8")) >= 0) return TuningConfidence.HIGH;
        if (sampleSize >= 30 && strength.compareTo(new BigDecimal("5")) >= 0) return TuningConfidence.MEDIUM;
        return TuningConfidence.LOW;
    }
}
