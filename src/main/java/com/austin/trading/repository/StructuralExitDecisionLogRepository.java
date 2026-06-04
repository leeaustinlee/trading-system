package com.austin.trading.repository;

import com.austin.trading.entity.StructuralExitDecisionLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface StructuralExitDecisionLogRepository extends JpaRepository<StructuralExitDecisionLogEntity, Long> {
    boolean existsBySourceReviewLogIdAndMode(Long sourceReviewLogId, String mode);
    List<StructuralExitDecisionLogEntity> findByModeAndEvaluationDateBetweenOrderByEvaluationDateAscIdAsc(String mode, LocalDate from, LocalDate to);
    @Query("""
            SELECT l FROM StructuralExitDecisionLogEntity l
             WHERE l.sourceDecisionStatus IN ('EXIT','STOP','REDUCE')
               AND l.id NOT IN (
                    SELECT o.structuralExitLogId FROM StopWashoutOutcomeEntity o
                     WHERE o.outcomeBasis = 'SOURCE_EXIT'
               )
             ORDER BY l.evaluatedAt ASC
            """)
    List<StructuralExitDecisionLogEntity> findSourceExitWithoutOutcome(Pageable pageable);

    @Query("""
            SELECT l FROM StructuralExitDecisionLogEntity l
             WHERE l.arbiterTier IN ('EXIT_REVIEW','HARD_EXIT_ALERT','REDUCE_REVIEW')
               AND l.id NOT IN (
                    SELECT o.structuralExitLogId FROM StopWashoutOutcomeEntity o
                     WHERE o.outcomeBasis = 'ARBITER_EXIT_SHADOW'
               )
             ORDER BY l.evaluatedAt ASC
            """)
    List<StructuralExitDecisionLogEntity> findArbiterExitWithoutOutcome(Pageable pageable);

    /** @deprecated use findSourceExitWithoutOutcome or findArbiterExitWithoutOutcome to keep universes explicit. */
    @Deprecated
    default List<StructuralExitDecisionLogEntity> findExitLikeWithoutOutcome(Pageable pageable) {
        return findSourceExitWithoutOutcome(pageable);
    }

    long countBySourceDecisionStatusIn(List<String> statuses);
    long countByArbiterTier(String arbiterTier);

    @Query("""
            SELECT l FROM StructuralExitDecisionLogEntity l
             WHERE l.arbiterTier IN :tiers
             ORDER BY l.evaluatedAt DESC
            """)
    List<StructuralExitDecisionLogEntity> findRecentByArbiterTiers(@Param("tiers") List<String> tiers, Pageable pageable);
}
