package com.austin.trading.engine;

import com.austin.trading.domain.enums.*;
import com.austin.trading.entity.StrategyTuningRecommendationEntity;
import com.austin.trading.entity.TuningAfterMetricsEntity;
import com.austin.trading.entity.TuningApplySnapshotEntity;
import com.austin.trading.entity.TuningEvaluationResultEntity;
import com.austin.trading.repository.StrategyTuningRecommendationRepository;
import com.austin.trading.repository.TuningAfterMetricsRepository;
import com.austin.trading.repository.TuningApplySnapshotRepository;
import com.austin.trading.repository.TuningEvaluationResultRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class TuningEvaluationEngine {
    public static final int MIN_SAMPLE_BEFORE = 10;
    public static final int MIN_SAMPLE_AFTER = 10;

    private final TuningApplySnapshotRepository snapshotRepository;
    private final TuningAfterMetricsRepository afterMetricsRepository;
    private final TuningEvaluationResultRepository resultRepository;
    private final StrategyTuningRecommendationRepository recommendationRepository;
    private final ObjectMapper objectMapper;

    public TuningEvaluationEngine(TuningApplySnapshotRepository snapshotRepository,
                                  TuningAfterMetricsRepository afterMetricsRepository,
                                  TuningEvaluationResultRepository resultRepository,
                                  StrategyTuningRecommendationRepository recommendationRepository,
                                  ObjectMapper objectMapper) {
        this.snapshotRepository = snapshotRepository;
        this.afterMetricsRepository = afterMetricsRepository;
        this.resultRepository = resultRepository;
        this.recommendationRepository = recommendationRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TuningEvaluationResultEntity evaluate(Long recommendationId) {
        TuningApplySnapshotEntity snapshot = snapshotRepository.findByRecommendationId(recommendationId).orElse(null);
        if (snapshot == null) {
            return saveResult(recommendationId, TuningEvaluationStatus.INSUFFICIENT_DATA,
                    "NO_BEFORE_SNAPSHOT: tuning_apply_snapshot is required before evaluation",
                    BigDecimal.ZERO, BigDecimal.ZERO, TuningFinalDecision.OBSERVE);
        }
        int beforeSample = sampleSize(snapshot.getStrategyMetricsJson());
        if (beforeSample < MIN_SAMPLE_BEFORE) {
            return saveResult(recommendationId, TuningEvaluationStatus.INSUFFICIENT_DATA,
                    "BEFORE_SAMPLE_TOO_SMALL: sample=" + beforeSample,
                    BigDecimal.ZERO, BigDecimal.ZERO, TuningFinalDecision.OBSERVE);
        }
        TuningAfterMetricsEntity after = afterMetricsRepository.findByRecommendationIdOrderByHorizonDaysDesc(recommendationId)
                .stream().findFirst().orElse(null);
        if (after == null) {
            return saveResult(recommendationId, TuningEvaluationStatus.INSUFFICIENT_DATA,
                    "NO_AFTER_METRICS: wait for T+5/T+10/T+20 after tracking",
                    BigDecimal.ZERO, BigDecimal.ZERO, TuningFinalDecision.OBSERVE);
        }
        if (after.getSampleSize() < MIN_SAMPLE_AFTER) {
            return saveResult(recommendationId, TuningEvaluationStatus.INSUFFICIENT_DATA,
                    "AFTER_SAMPLE_TOO_SMALL: sample=" + after.getSampleSize(),
                    BigDecimal.ZERO, BigDecimal.ZERO, TuningFinalDecision.OBSERVE);
        }
        if (after.getHorizonDays() < 5) {
            return saveResult(recommendationId, TuningEvaluationStatus.INSUFFICIENT_DATA,
                    "WINDOW_TOO_SHORT: horizon=" + after.getHorizonDays(),
                    BigDecimal.ZERO, BigDecimal.ZERO, TuningFinalDecision.OBSERVE);
        }

        BigDecimal deltaReturn = diff(after.getAvgReturn(), snapshot.getDecisionAvgReturn());
        BigDecimal deltaWinRate = diff(after.getWinRate(), snapshot.getDecisionWinRate());
        BigDecimal deltaMae = diff(after.getAvgMae(), snapshot.getDecisionAvgMae());
        BigDecimal deltaRelative = after.getAvgRelativeReturn() == null ? BigDecimal.ZERO : after.getAvgRelativeReturn();
        BigDecimal improvementScore = scale(deltaReturn.add(deltaWinRate.multiply(new BigDecimal("10"))));
        BigDecimal riskScore = scale(deltaMae.negate());

        if (lte(deltaReturn, "-1.0") || lte(deltaWinRate, "-0.08") || lte(deltaMae, "-2.0")
                || (lte(deltaRelative, "-2.0") && lt(after.getAvgReturn(), "0"))) {
            TuningEvaluationResultEntity result = saveResult(recommendationId, TuningEvaluationStatus.FAIL,
                    "FAIL: avgReturn/winRate/avgMAE deteriorated; deltaReturn=" + deltaReturn
                            + ", deltaWinRate=" + deltaWinRate + ", deltaAvgMAE=" + deltaMae,
                    improvementScore, riskScore, TuningFinalDecision.ROLLBACK);
            createRollbackSuggestion(recommendationId, result);
            return result;
        }
        if (gte(deltaReturn, "1.0") && gte(deltaWinRate, "0.05") && gte(deltaMae, "-1.0")
                && gte(deltaRelative, "0")) {
            return saveResult(recommendationId, TuningEvaluationStatus.SUCCESS,
                    "SUCCESS: avgReturn and winRate improved without material MAE deterioration; deltaReturn="
                            + deltaReturn + ", deltaWinRate=" + deltaWinRate + ", deltaAvgMAE=" + deltaMae,
                    improvementScore, riskScore, TuningFinalDecision.KEEP);
        }
        return saveResult(recommendationId, TuningEvaluationStatus.INCONCLUSIVE,
                "INCONCLUSIVE: sample is sufficient but movement is not significant; deltaReturn=" + deltaReturn
                        + ", deltaWinRate=" + deltaWinRate + ", deltaAvgMAE=" + deltaMae,
                improvementScore, riskScore, TuningFinalDecision.OBSERVE);
    }

    private void createRollbackSuggestion(Long recommendationId, TuningEvaluationResultEntity result) {
        StrategyTuningRecommendationEntity original = recommendationRepository.findById(recommendationId).orElse(null);
        if (original == null) return;
        StrategyTuningRecommendationEntity suggestion = new StrategyTuningRecommendationEntity();
        suggestion.setGeneratedDate(java.time.LocalDate.now());
        suggestion.setLookbackDays(original.getLookbackDays());
        suggestion.setRecommendationType(TuningRecommendationType.ROLLBACK_SUGGESTION);
        suggestion.setTargetModule(original.getTargetModule());
        suggestion.setTargetParameter(original.getTargetParameter());
        suggestion.setCurrentValue(original.getSuggestedValue());
        suggestion.setSuggestedValue(original.getRollbackValue());
        suggestion.setRollbackValue(original.getSuggestedValue());
        suggestion.setSuggestedAction("建議人工審核是否回滾，不自動 rollback");
        suggestion.setReason("After tuning evaluation FAIL; evaluationResultId=" + result.getId()
                + ", recommendationId=" + recommendationId);
        suggestion.setEvidenceJson("{\"source\":\"tuning_evaluation_result\",\"evaluationResultId\":" + result.getId()
                + ",\"recommendationId\":" + recommendationId + "}");
        suggestion.setSampleSize(original.getSampleSize());
        suggestion.setConfidence(TuningConfidence.HIGH);
        suggestion.setStatus(TuningRecommendationStatus.PENDING);
        recommendationRepository.save(suggestion);
    }

    private TuningEvaluationResultEntity saveResult(Long recommendationId, TuningEvaluationStatus status, String reason,
                                                    BigDecimal improvementScore, BigDecimal riskScore,
                                                    TuningFinalDecision decision) {
        TuningEvaluationResultEntity result = new TuningEvaluationResultEntity();
        result.setRecommendationId(recommendationId);
        result.setEvaluationStatus(status);
        result.setEvaluationReason(reason);
        result.setImprovementScore(improvementScore);
        result.setRiskScore(riskScore);
        result.setFinalDecision(decision);
        return resultRepository.save(result);
    }

    private int sampleSize(String json) {
        if (json == null || json.isBlank()) return 0;
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.path("sampleSize").asInt(0);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private BigDecimal diff(BigDecimal after, BigDecimal before) {
        if (after == null || before == null) return BigDecimal.ZERO;
        return scale(after.subtract(before));
    }

    private boolean gte(BigDecimal value, String threshold) { return value.compareTo(new BigDecimal(threshold)) >= 0; }
    private boolean lte(BigDecimal value, String threshold) { return value.compareTo(new BigDecimal(threshold)) <= 0; }
    private boolean lt(BigDecimal value, String threshold) { return value != null && value.compareTo(new BigDecimal(threshold)) < 0; }
    private BigDecimal scale(BigDecimal value) { return value.setScale(4, RoundingMode.HALF_UP); }
}
