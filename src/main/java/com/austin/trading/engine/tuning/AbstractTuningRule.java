package com.austin.trading.engine.tuning;

import com.austin.trading.domain.enums.TuningConfidence;
import com.austin.trading.domain.enums.TuningRecommendationStatus;
import com.austin.trading.domain.enums.TuningRecommendationType;
import com.austin.trading.entity.StrategyTuningRecommendationEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

abstract class AbstractTuningRule implements TuningRule {
    protected final TuningConfidenceCalculator confidenceCalculator;
    protected final ObjectMapper objectMapper;

    protected AbstractTuningRule(TuningConfidenceCalculator confidenceCalculator, ObjectMapper objectMapper) {
        this.confidenceCalculator = confidenceCalculator;
        this.objectMapper = objectMapper;
    }

    protected StrategyTuningRecommendationEntity recommendation(
            LocalDate asOfDate,
            int lookbackDays,
            TuningRecommendationType type,
            String targetParameter,
            String currentValue,
            String suggestedValue,
            String action,
            String reason,
            Map<String, Object> evidence,
            int sampleSize,
            BigDecimal winRate,
            BigDecimal avgReturnPct,
            BigDecimal avgMfePct,
            BigDecimal avgMaePct,
            BigDecimal missedRallyRate,
            BigDecimal avgRelativeReturnPct,
            TuningConfidence confidence
    ) {
        StrategyTuningRecommendationEntity e = new StrategyTuningRecommendationEntity();
        e.setGeneratedDate(asOfDate);
        e.setLookbackDays(lookbackDays);
        e.setRecommendationType(type);
        e.setTargetModule(moduleOf(targetParameter));
        e.setTargetParameter(targetParameter);
        e.setCurrentValue(currentValue);
        e.setSuggestedValue(suggestedValue);
        e.setSuggestedAction(action);
        e.setReason(reason);
        e.setEvidenceJson(toJson(evidence));
        e.setSampleSize(sampleSize);
        e.setWinRate(winRate);
        e.setAvgReturnPct(avgReturnPct);
        e.setAvgMfePct(avgMfePct);
        e.setAvgMaePct(avgMaePct);
        e.setMissedRallyRate(missedRallyRate);
        e.setBenchmarkRelativeReturnPct(avgRelativeReturnPct);
        e.setConfidence(confidence);
        e.setStatus(TuningRecommendationStatus.PENDING);
        return e;
    }

    protected String moduleOf(String key) {
        int idx = key == null ? -1 : key.indexOf('.');
        return idx > 0 ? key.substring(0, idx) : "strategy";
    }

    protected String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
