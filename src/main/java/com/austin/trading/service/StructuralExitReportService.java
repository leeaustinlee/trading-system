package com.austin.trading.service;

import com.austin.trading.domain.enums.StopWashoutOutcomeLabel;
import com.austin.trading.domain.enums.StructuralExitTier;
import com.austin.trading.entity.StopWashoutOutcomeEntity;
import com.austin.trading.entity.StructuralExitDecisionLogEntity;
import com.austin.trading.repository.StopWashoutOutcomeRepository;
import com.austin.trading.repository.StructuralExitDecisionLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

@Service
public class StructuralExitReportService {
    private final StructuralExitDecisionLogRepository logRepo; private final StopWashoutOutcomeRepository outcomeRepo;
    public StructuralExitReportService(StructuralExitDecisionLogRepository logRepo, StopWashoutOutcomeRepository outcomeRepo) { this.logRepo=logRepo; this.outcomeRepo=outcomeRepo; }
    public Map<String,Object> summary() {
        long sourceExit = logRepo.countBySourceDecisionStatusIn(List.of("EXIT","STOP","REDUCE"));
        long exitReview = logRepo.countByArbiterTier(StructuralExitTier.EXIT_REVIEW.name());
        long observe = logRepo.countByArbiterTier(StructuralExitTier.OBSERVE_1D.name());
        long hard = logRepo.countByArbiterTier(StructuralExitTier.HARD_EXIT_ALERT.name());
        long gap = logRepo.countByArbiterTier(StructuralExitTier.DATA_GAP.name());
        long sourceWash = outcomeRepo.countByOutcomeBasisAndOutcomeLabel(StopWashoutOutcomeEntity.BASIS_SOURCE_EXIT, StopWashoutOutcomeLabel.WASHOUT_REVERSAL.name());
        long sourceOut = outcomeRepo.countByOutcomeBasis(StopWashoutOutcomeEntity.BASIS_SOURCE_EXIT);
        long shadowDen = outcomeRepo.countByOutcomeBasisAndSignalTierIn(StopWashoutOutcomeEntity.BASIS_ARBITER_EXIT_SHADOW, List.of(StructuralExitTier.EXIT_REVIEW.name(), StructuralExitTier.HARD_EXIT_ALERT.name(), StructuralExitTier.REDUCE_REVIEW.name()));
        long shadowWash = outcomeRepo.countByOutcomeBasisAndSignalTierInAndOutcomeLabel(StopWashoutOutcomeEntity.BASIS_ARBITER_EXIT_SHADOW, List.of(StructuralExitTier.EXIT_REVIEW.name(), StructuralExitTier.HARD_EXIT_ALERT.name(), StructuralExitTier.REDUCE_REVIEW.name()), StopWashoutOutcomeLabel.WASHOUT_REVERSAL.name());
        return Map.of(
                "source_exit_count", sourceExit,
                "arbiter_exit_review_count", exitReview,
                "arbiter_observe_count", observe,
                "hard_exit_preserved_count", hard,
                "washout_rate_source", pct(sourceWash, sourceOut),
                "washout_rate_arbiter_shadow", pct(shadowWash, shadowDen),
                "data_gap_count", gap,
                "top_false_exit_cases", logRepo.findRecentByArbiterTiers(List.of(StructuralExitTier.OBSERVE_1D.name(), StructuralExitTier.HOLD_THESIS.name()), PageRequest.of(0, 20)).stream().map(this::caseRow).toList()
        );
    }
    private BigDecimal pct(long n,long d){ if(d<=0) return BigDecimal.ZERO; return BigDecimal.valueOf(n).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(d),2, RoundingMode.HALF_UP); }
    private Map<String,Object> caseRow(StructuralExitDecisionLogEntity e){ return Map.of("id", e.getId(), "symbol", e.getSymbol(), "source_decision", e.getSourceDecisionStatus(), "arbiter_tier", e.getArbiterTier(), "reason", e.getArbiterReason(), "evaluated_at", e.getEvaluatedAt()); }
}
