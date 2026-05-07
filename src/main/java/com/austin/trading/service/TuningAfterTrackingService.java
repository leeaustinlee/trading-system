package com.austin.trading.service;

import com.austin.trading.domain.enums.TuningRecommendationStatus;
import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.StrategyTuningRecommendationEntity;
import com.austin.trading.entity.TuningAfterMetricsEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.StrategyTuningRecommendationRepository;
import com.austin.trading.repository.TuningAfterMetricsRepository;
import com.austin.trading.repository.TuningApplySnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class TuningAfterTrackingService {
    private static final int[] HORIZONS = {5, 10, 20};

    private final StrategyTuningRecommendationRepository recommendationRepository;
    private final TuningApplySnapshotRepository snapshotRepository;
    private final TuningAfterMetricsRepository afterMetricsRepository;
    private final CandidateForwardTrackingRepository candidateRepository;

    public TuningAfterTrackingService(StrategyTuningRecommendationRepository recommendationRepository,
                                      TuningApplySnapshotRepository snapshotRepository,
                                      TuningAfterMetricsRepository afterMetricsRepository,
                                      CandidateForwardTrackingRepository candidateRepository) {
        this.recommendationRepository = recommendationRepository;
        this.snapshotRepository = snapshotRepository;
        this.afterMetricsRepository = afterMetricsRepository;
        this.candidateRepository = candidateRepository;
    }

    @Transactional
    public List<TuningAfterMetricsEntity> run(LocalDate evaluationDate) {
        List<TuningAfterMetricsEntity> written = new ArrayList<>();
        for (StrategyTuningRecommendationEntity recommendation :
                recommendationRepository.findByStatusOrderByCreatedAtDesc(TuningRecommendationStatus.APPLIED)) {
            written.addAll(calculateDueMetrics(recommendation, evaluationDate));
        }
        return written;
    }

    @Transactional
    public List<TuningAfterMetricsEntity> calculateDueMetrics(StrategyTuningRecommendationEntity recommendation,
                                                              LocalDate evaluationDate) {
        if (recommendation.getAppliedAt() == null) return List.of();
        if (snapshotRepository.findByRecommendationId(recommendation.getId()).isEmpty()) return List.of();

        LocalDate appliedDate = recommendation.getAppliedAt().toLocalDate();
        List<CandidateForwardTrackingEntity> afterRows = candidateRepository.findByTradingDateGreaterThanEqual(appliedDate)
                .stream()
                .filter(r -> !r.getTradingDate().isAfter(evaluationDate))
                .filter(r -> matchesBucket(recommendation.getTargetParameter(), r))
                .toList();

        List<TuningAfterMetricsEntity> written = new ArrayList<>();
        for (int horizon : HORIZONS) {
            if (evaluationDate.isBefore(appliedDate.plusDays(horizon))) continue;
            if (afterMetricsRepository.findByRecommendationIdAndHorizonDays(recommendation.getId(), horizon).isPresent()) {
                continue;
            }
            List<CandidateForwardTrackingEntity> horizonRows = afterRows.stream()
                    .filter(r -> !r.getTradingDate().isAfter(appliedDate.plusDays(horizon)))
                    .toList();
            var metrics = TuningMetricsCalculator.calculate(horizonRows, horizon);
            TuningAfterMetricsEntity entity = TuningMetricsCalculator.toAfterMetrics(
                    recommendation.getId(), evaluationDate, horizon, metrics);
            written.add(afterMetricsRepository.save(entity));
        }
        return written;
    }

    private boolean matchesBucket(String targetParameter, CandidateForwardTrackingEntity row) {
        if (targetParameter == null) return true;
        if (targetParameter.startsWith("breakout.")) return "BREAKOUT".equalsIgnoreCase(row.getPrimaryStrategy());
        if (targetParameter.startsWith("pullback.")) return "PULLBACK".equalsIgnoreCase(row.getPrimaryStrategy());
        if (targetParameter.startsWith("continuation.")) return "CONTINUATION".equalsIgnoreCase(row.getPrimaryStrategy());
        if ("scoring.enter_min_score".equals(targetParameter) || targetParameter.startsWith("risk.")) {
            return "ENTER".equalsIgnoreCase(row.getFinalDecision());
        }
        if ("scoring.watch_min_score".equals(targetParameter)) {
            return "ENTER".equalsIgnoreCase(row.getFinalDecision()) || "WATCH".equalsIgnoreCase(row.getFinalDecision());
        }
        return true;
    }
}
