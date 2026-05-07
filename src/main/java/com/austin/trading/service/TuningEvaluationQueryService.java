package com.austin.trading.service;

import com.austin.trading.domain.enums.TuningEvaluationStatus;
import com.austin.trading.domain.enums.TuningRecommendationStatus;
import com.austin.trading.domain.enums.TuningRecommendationType;
import com.austin.trading.dto.response.*;
import com.austin.trading.engine.TuningEvaluationEngine;
import com.austin.trading.repository.StrategyTuningRecommendationRepository;
import com.austin.trading.repository.TuningAfterMetricsRepository;
import com.austin.trading.repository.TuningApplySnapshotRepository;
import com.austin.trading.repository.TuningEvaluationResultRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class TuningEvaluationQueryService {
    private final TuningEvaluationEngine engine;
    private final StrategyTuningService tuningService;
    private final StrategyTuningRecommendationRepository recommendationRepository;
    private final TuningApplySnapshotRepository snapshotRepository;
    private final TuningAfterMetricsRepository afterMetricsRepository;
    private final TuningEvaluationResultRepository resultRepository;

    public TuningEvaluationQueryService(TuningEvaluationEngine engine,
                                        StrategyTuningService tuningService,
                                        StrategyTuningRecommendationRepository recommendationRepository,
                                        TuningApplySnapshotRepository snapshotRepository,
                                        TuningAfterMetricsRepository afterMetricsRepository,
                                        TuningEvaluationResultRepository resultRepository) {
        this.engine = engine;
        this.tuningService = tuningService;
        this.recommendationRepository = recommendationRepository;
        this.snapshotRepository = snapshotRepository;
        this.afterMetricsRepository = afterMetricsRepository;
        this.resultRepository = resultRepository;
    }

    @Transactional
    public TuningEvaluationResultDto evaluate(Long recommendationId) {
        return TuningEvaluationResultDto.from(engine.evaluate(recommendationId));
    }

    @Transactional(readOnly = true)
    public TuningEvaluationDetailDto getDetail(Long recommendationId) {
        return new TuningEvaluationDetailDto(
                tuningService.getRecommendation(recommendationId),
                snapshotRepository.findByRecommendationId(recommendationId).map(TuningApplySnapshotDto::from).orElse(null),
                afterMetricsRepository.findByRecommendationIdOrderByHorizonDaysDesc(recommendationId).stream()
                        .map(TuningAfterMetricsDto::from).toList(),
                resultRepository.findFirstByRecommendationIdOrderByCreatedAtDesc(recommendationId)
                        .map(TuningEvaluationResultDto::from).orElse(null)
        );
    }

    @Transactional(readOnly = true)
    public TuningEvaluationSummaryDto getSummary() {
        long success = resultRepository.countByEvaluationStatus(TuningEvaluationStatus.SUCCESS);
        long fail = resultRepository.countByEvaluationStatus(TuningEvaluationStatus.FAIL);
        long total = success + fail;
        BigDecimal successRate = total == 0 ? BigDecimal.ZERO :
                BigDecimal.valueOf(success).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
        return new TuningEvaluationSummaryDto(
                success,
                fail,
                successRate,
                resultRepository.findFirstByEvaluationStatusOrderByCreatedAtDesc(TuningEvaluationStatus.SUCCESS)
                        .map(TuningEvaluationResultDto::from).orElse(null),
                resultRepository.findFirstByEvaluationStatusOrderByCreatedAtDesc(TuningEvaluationStatus.FAIL)
                        .map(TuningEvaluationResultDto::from).orElse(null),
                recommendationRepository.countByRecommendationTypeAndStatus(
                        TuningRecommendationType.ROLLBACK_SUGGESTION, TuningRecommendationStatus.PENDING)
        );
    }
}
