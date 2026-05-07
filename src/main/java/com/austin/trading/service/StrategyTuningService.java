package com.austin.trading.service;

import com.austin.trading.domain.enums.TuningConfidence;
import com.austin.trading.domain.enums.TuningRecommendationStatus;
import com.austin.trading.dto.response.ScoreConfigResponse;
import com.austin.trading.dto.response.StrategyTuningRecommendationDto;
import com.austin.trading.dto.response.StrategyTuningSummaryDto;
import com.austin.trading.engine.StrategyTuningEngine;
import com.austin.trading.entity.StrategyTuningHistoryEntity;
import com.austin.trading.entity.StrategyTuningRecommendationEntity;
import com.austin.trading.repository.StrategyTuningHistoryRepository;
import com.austin.trading.repository.StrategyTuningRecommendationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class StrategyTuningService {
    private final StrategyTuningEngine engine;
    private final StrategyTuningRecommendationRepository recommendationRepository;
    private final StrategyTuningHistoryRepository historyRepository;
    private final ScoreConfigService scoreConfigService;
    private final TuningApplySnapshotService tuningApplySnapshotService;

    @Autowired
    public StrategyTuningService(StrategyTuningEngine engine,
                                 StrategyTuningRecommendationRepository recommendationRepository,
                                 StrategyTuningHistoryRepository historyRepository,
                                 ScoreConfigService scoreConfigService,
                                 TuningApplySnapshotService tuningApplySnapshotService) {
        this.engine = engine;
        this.recommendationRepository = recommendationRepository;
        this.historyRepository = historyRepository;
        this.scoreConfigService = scoreConfigService;
        this.tuningApplySnapshotService = tuningApplySnapshotService;
    }

    public StrategyTuningService(StrategyTuningEngine engine,
                                 StrategyTuningRecommendationRepository recommendationRepository,
                                 StrategyTuningHistoryRepository historyRepository,
                                 ScoreConfigService scoreConfigService) {
        this(engine, recommendationRepository, historyRepository, scoreConfigService, null);
    }

    @Transactional
    public List<StrategyTuningRecommendationDto> generateDailyRecommendations() {
        return generateRecommendations(LocalDate.now(), 20);
    }

    @Transactional
    public List<StrategyTuningRecommendationDto> generateRecommendations(LocalDate asOfDate, int lookbackDays) {
        List<StrategyTuningRecommendationEntity> drafts = engine.generateRecommendations(asOfDate, lookbackDays);
        return drafts.stream()
                .peek(this::attachCurrentConfigValue)
                .map(recommendationRepository::save)
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StrategyTuningRecommendationDto> listRecommendations() {
        return recommendationRepository.findAllByOrderByCreatedAtDesc().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public StrategyTuningRecommendationDto getRecommendation(Long id) {
        return toDto(find(id));
    }

    @Transactional
    public StrategyTuningRecommendationDto approveRecommendation(Long id, String approvedBy) {
        StrategyTuningRecommendationEntity e = find(id);
        requireStatus(e, TuningRecommendationStatus.PENDING, "只有 PENDING 建議可 approve");
        e.setStatus(TuningRecommendationStatus.APPROVED);
        e.setApprovedBy(approvedBy);
        e.setApprovedAt(LocalDateTime.now());
        return toDto(recommendationRepository.save(e));
    }

    @Transactional
    public StrategyTuningRecommendationDto rejectRecommendation(Long id, String rejectedBy, String reason) {
        StrategyTuningRecommendationEntity e = find(id);
        requireStatus(e, TuningRecommendationStatus.PENDING, "只有 PENDING 建議可 reject");
        e.setStatus(TuningRecommendationStatus.REJECTED);
        e.setRejectedBy(rejectedBy);
        e.setRejectedAt(LocalDateTime.now());
        if (reason != null && !reason.isBlank()) {
            e.setReason((e.getReason() == null ? "" : e.getReason() + " ") + "Reject reason: " + reason);
        }
        return toDto(recommendationRepository.save(e));
    }

    @Transactional
    public StrategyTuningRecommendationDto applyApprovedRecommendation(Long id, String appliedBy) {
        StrategyTuningRecommendationEntity e = find(id);
        requireStatus(e, TuningRecommendationStatus.APPROVED, "只有 APPROVED 建議可 apply");
        ScoreConfigResponse current = scoreConfigService.getByKey(e.getTargetParameter());
        String oldValue = current.configValue();
        LocalDateTime appliedAt = LocalDateTime.now();

        StrategyTuningHistoryEntity history = new StrategyTuningHistoryEntity();
        history.setRecommendationId(e.getId());
        history.setAppliedDate(appliedAt);
        history.setTargetModule(e.getTargetModule());
        history.setTargetParameter(e.getTargetParameter());
        history.setOldValue(oldValue);
        history.setNewValue(e.getSuggestedValue());
        history.setAppliedBy(appliedBy);
        history.setRollbackValue(oldValue);
        history.setBeforeMetricsJson(e.getEvidenceJson());
        historyRepository.save(history);
        if (tuningApplySnapshotService != null) {
            tuningApplySnapshotService.writeSnapshot(e, appliedAt.toLocalDate());
        }

        scoreConfigService.update(e.getTargetParameter(), e.getSuggestedValue());
        e.setRollbackValue(oldValue);
        e.setAppliedAt(appliedAt);
        e.setStatus(TuningRecommendationStatus.APPLIED);
        return toDto(recommendationRepository.save(e));
    }

    @Transactional
    public StrategyTuningRecommendationDto rollbackRecommendation(Long id, String rolledBackBy) {
        StrategyTuningRecommendationEntity e = find(id);
        requireStatus(e, TuningRecommendationStatus.APPLIED, "只有 APPLIED 建議可 rollback");
        if (e.getRollbackValue() == null) throw new IllegalStateException("缺少 rollback value，拒絕回滾");
        String currentValue = scoreConfigService.getByKey(e.getTargetParameter()).configValue();
        scoreConfigService.update(e.getTargetParameter(), e.getRollbackValue());

        StrategyTuningHistoryEntity history = new StrategyTuningHistoryEntity();
        history.setRecommendationId(e.getId());
        history.setAppliedDate(LocalDateTime.now());
        history.setTargetModule(e.getTargetModule());
        history.setTargetParameter(e.getTargetParameter());
        history.setOldValue(currentValue);
        history.setNewValue(e.getRollbackValue());
        history.setAppliedBy(rolledBackBy);
        history.setRollbackValue(currentValue);
        history.setBeforeMetricsJson(e.getEvidenceJson());
        history.setAfterMetricsJson("{\"action\":\"ROLLBACK\"}");
        historyRepository.save(history);

        e.setStatus(TuningRecommendationStatus.ROLLED_BACK);
        return toDto(recommendationRepository.save(e));
    }

    @Transactional(readOnly = true)
    public StrategyTuningSummaryDto getTuningSummary() {
        long pending = recommendationRepository.countByStatus(TuningRecommendationStatus.PENDING);
        StrategyTuningRecommendationDto latestHigh = recommendationRepository
                .findFirstByConfidenceOrderByCreatedAtDesc(TuningConfidence.HIGH)
                .map(this::toDto)
                .orElse(null);
        String warning = pending > 0 ? "目前有 " + pending + " 筆策略調參建議待審核" : "樣本不足，不建議調參";
        return new StrategyTuningSummaryDto(pending, latestHigh, warning);
    }

    public StrategyTuningRecommendationDto toDto(StrategyTuningRecommendationEntity e) {
        return new StrategyTuningRecommendationDto(e.getId(), e.getGeneratedDate(), e.getLookbackDays(),
                name(e.getRecommendationType()), e.getTargetModule(), e.getTargetParameter(), e.getCurrentValue(),
                e.getSuggestedValue(), e.getSuggestedAction(), e.getReason(), e.getEvidenceJson(), e.getSampleSize(),
                e.getWinRate(), e.getAvgReturnPct(), e.getAvgMfePct(), e.getAvgMaePct(), e.getMissedRallyRate(),
                e.getBenchmarkRelativeReturnPct(), name(e.getConfidence()), name(e.getStatus()), e.getApprovedBy(),
                e.getApprovedAt(), e.getRejectedBy(), e.getRejectedAt(), e.getAppliedAt(), e.getRollbackValue(),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    private void attachCurrentConfigValue(StrategyTuningRecommendationEntity e) {
        try {
            e.setCurrentValue(scoreConfigService.getByKey(e.getTargetParameter()).configValue());
        } catch (RuntimeException ignored) {
            // 設定不存在時保留 rule 的 currentValue，apply 階段仍會失敗，不會靜默寫入未知 key。
        }
    }

    private StrategyTuningRecommendationEntity find(Long id) {
        return recommendationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Strategy tuning recommendation not found: " + id));
    }

    private void requireStatus(StrategyTuningRecommendationEntity e, TuningRecommendationStatus status, String message) {
        if (e.getStatus() != status) throw new IllegalStateException(message);
    }

    private String name(Enum<?> e) {
        return e == null ? null : e.name();
    }
}
