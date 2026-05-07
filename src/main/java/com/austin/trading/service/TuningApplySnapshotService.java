package com.austin.trading.service;

import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.StrategyTuningRecommendationEntity;
import com.austin.trading.entity.TuningApplySnapshotEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.TuningApplySnapshotRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class TuningApplySnapshotService {
    private final CandidateForwardTrackingRepository candidateRepository;
    private final TuningApplySnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;

    public TuningApplySnapshotService(CandidateForwardTrackingRepository candidateRepository,
                                      TuningApplySnapshotRepository snapshotRepository,
                                      ObjectMapper objectMapper) {
        this.candidateRepository = candidateRepository;
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TuningApplySnapshotEntity writeSnapshot(StrategyTuningRecommendationEntity recommendation,
                                                   LocalDate appliedDate) {
        LocalDate start = appliedDate.minusDays(Math.max(recommendation.getLookbackDays(), 20));
        LocalDate end = appliedDate.minusDays(1);
        List<CandidateForwardTrackingEntity> rows = candidateRepository.findByTradingDateBetween(start, end).stream()
                .filter(r -> matchesBucket(recommendation.getTargetParameter(), r))
                .toList();
        TuningMetricsCalculator.Metrics metrics = TuningMetricsCalculator.calculate(rows, 5);

        TuningApplySnapshotEntity snapshot = new TuningApplySnapshotEntity();
        snapshot.setRecommendationId(recommendation.getId());
        snapshot.setAppliedDate(appliedDate);
        snapshot.setLookbackDays(recommendation.getLookbackDays());
        snapshot.setDecisionWinRate(metrics.winRate() != null ? metrics.winRate() : recommendation.getWinRate());
        snapshot.setDecisionAvgReturn(metrics.avgReturn() != null ? metrics.avgReturn() : recommendation.getAvgReturnPct());
        snapshot.setDecisionAvgMfe(metrics.avgMfe() != null ? metrics.avgMfe() : recommendation.getAvgMfePct());
        snapshot.setDecisionAvgMae(metrics.avgMae() != null ? metrics.avgMae() : recommendation.getAvgMaePct());
        snapshot.setStrategyMetricsJson(json(Map.of(
                "sampleSize", metrics.sampleSize(),
                "source", metrics.sampleSize() > 0 ? "candidate_forward_tracking" : "recommendation_evidence_fallback",
                "targetParameter", recommendation.getTargetParameter(),
                "windowStart", start.toString(),
                "windowEnd", end.toString(),
                "sampleIds", rows.stream().map(CandidateForwardTrackingEntity::getId).toList()
        )));
        snapshot.setGateMetricsJson(json(Map.of(
                "targetModule", recommendation.getTargetModule(),
                "targetParameter", recommendation.getTargetParameter()
        )));
        snapshot.setScoreBucketMetricsJson(json(Map.of(
                "bucketFilter", bucketDescription(recommendation.getTargetParameter())
        )));
        return snapshotRepository.save(snapshot);
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

    private String bucketDescription(String targetParameter) {
        if (targetParameter == null) return "ALL";
        if (targetParameter.startsWith("breakout.")) return "primary_strategy=BREAKOUT";
        if (targetParameter.startsWith("pullback.")) return "primary_strategy=PULLBACK";
        if (targetParameter.startsWith("continuation.")) return "primary_strategy=CONTINUATION";
        if ("scoring.enter_min_score".equals(targetParameter) || targetParameter.startsWith("risk.")) return "final_decision=ENTER";
        if ("scoring.watch_min_score".equals(targetParameter)) return "final_decision IN (ENTER,WATCH)";
        return "ALL";
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize tuning snapshot JSON", e);
        }
    }
}
