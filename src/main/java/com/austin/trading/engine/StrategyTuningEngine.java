package com.austin.trading.engine;

import com.austin.trading.engine.tuning.*;
import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.MissedRallyTrackingEntity;
import com.austin.trading.entity.StrategyTuningRecommendationEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.MissedRallyTrackingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class StrategyTuningEngine {
    private final CandidateForwardTrackingRepository candidateRepository;
    private final MissedRallyTrackingRepository missedRallyRepository;
    private final List<TuningRule> rules;

    public StrategyTuningEngine(CandidateForwardTrackingRepository candidateRepository,
                                MissedRallyTrackingRepository missedRallyRepository,
                                ObjectMapper objectMapper) {
        this.candidateRepository = candidateRepository;
        this.missedRallyRepository = missedRallyRepository;
        TuningConfidenceCalculator calculator = new TuningConfidenceCalculator();
        this.rules = List.of(
                new RejectRallyTuningRule(calculator, objectMapper),
                new EnterWeakPerformanceRule(calculator, objectMapper),
                new WatchOutperformRule(calculator, objectMapper),
                new BreakoutMissedRallyRule(calculator, objectMapper),
                new PullbackWeaknessRule(calculator, objectMapper),
                new ContinuationOutperformanceRule(calculator, objectMapper)
        );
    }

    @Transactional(readOnly = true)
    public List<StrategyTuningRecommendationEntity> generateRecommendations(LocalDate asOfDate, int lookbackDays) {
        LocalDate end = asOfDate.minusDays(5);
        LocalDate start = asOfDate.minusDays(Math.max(lookbackDays, TuningStats.MIN_SAMPLE) + 5L);
        List<CandidateForwardTrackingEntity> candidateRows = candidateRepository.findByTradingDateBetween(start, end);
        List<MissedRallyTrackingEntity> missedRallyRows = missedRallyRepository.findByTradingDateBetween(start, end);

        analyzeDecisionPerformance(candidateRows);
        analyzeStrategyPerformance(candidateRows);
        analyzeGateMissedRallies(missedRallyRows);
        analyzeScoreBuckets(candidateRows);
        analyzeRiskMetrics(candidateRows);

        List<StrategyTuningRecommendationEntity> recommendations = new ArrayList<>();
        for (TuningRule rule : rules) {
            recommendations.addAll(rule.evaluate(asOfDate, lookbackDays, candidateRows, missedRallyRows));
        }
        return recommendations.stream()
                .filter(r -> r.getConfidence() != null)
                .filter(r -> !"INSUFFICIENT_DATA".equals(r.getConfidence().name()))
                .collect(Collectors.toMap(StrategyTuningRecommendationEntity::getTargetParameter, r -> r,
                        this::preferHigherConfidence))
                .values().stream()
                .map(this::buildRecommendation)
                .toList();
    }

    public Map<String, TuningStats.CandidateStats> analyzeDecisionPerformance(List<CandidateForwardTrackingEntity> rows) {
        return rows.stream().collect(Collectors.groupingBy(r -> safe(r.getFinalDecision()))).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> TuningStats.candidateStats(e.getValue())));
    }

    public Map<String, TuningStats.CandidateStats> analyzeStrategyPerformance(List<CandidateForwardTrackingEntity> rows) {
        return rows.stream().collect(Collectors.groupingBy(r -> safe(r.getPrimaryStrategy()))).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> TuningStats.candidateStats(e.getValue())));
    }

    public Map<String, TuningStats.MissedRallyStats> analyzeGateMissedRallies(List<MissedRallyTrackingEntity> rows) {
        return rows.stream().collect(Collectors.groupingBy(r -> safe(r.getGateName()))).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> TuningStats.missedRallyStats(e.getValue())));
    }

    public Map<String, TuningStats.CandidateStats> analyzeScoreBuckets(List<CandidateForwardTrackingEntity> rows) {
        return rows.stream().collect(Collectors.groupingBy(r -> {
            if (r.getFinalScore() == null) return "UNKNOWN";
            if (r.getFinalScore().compareTo(new java.math.BigDecimal("5")) < 0) return "<5";
            if (r.getFinalScore().compareTo(new java.math.BigDecimal("6")) < 0) return "5-6";
            if (r.getFinalScore().compareTo(new java.math.BigDecimal("7")) < 0) return "6-7";
            if (r.getFinalScore().compareTo(new java.math.BigDecimal("8")) < 0) return "7-8";
            return "8+";
        })).entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> TuningStats.candidateStats(e.getValue())));
    }

    public TuningStats.CandidateStats analyzeRiskMetrics(List<CandidateForwardTrackingEntity> rows) {
        return TuningStats.candidateStats(rows);
    }

    public StrategyTuningRecommendationEntity buildRecommendation(StrategyTuningRecommendationEntity e) {
        return e;
    }

    private StrategyTuningRecommendationEntity preferHigherConfidence(StrategyTuningRecommendationEntity a,
                                                                      StrategyTuningRecommendationEntity b) {
        return a.getConfidence().ordinal() <= b.getConfidence().ordinal() ? a : b;
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }
}
