package com.austin.trading.repository;

import com.austin.trading.entity.PositionReviewLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PositionReviewLogRepository extends JpaRepository<PositionReviewLogEntity, Long> {

    List<PositionReviewLogEntity> findByReviewDateOrderByCreatedAtDesc(LocalDate reviewDate);

    List<PositionReviewLogEntity> findByReviewDateBetweenOrderByReviewDateAscIdAsc(LocalDate from, LocalDate to);

    /**
     * Latest review for a position.
     *
     * <p>Use id ordering instead of created_at: existing local DB rows can have created_at=NULL,
     * which made newer STRONG reviews sort behind an older EXIT row and polluted mobile/AI output.</p>
     */
    Optional<PositionReviewLogEntity> findTopByPositionIdOrderByIdDesc(Long positionId);

    List<PositionReviewLogEntity> findByPositionIdOrderByIdDesc(Long positionId);

    /** Latest review log row for a given symbol (paper_trade rows aren't keyed to position_id). */
    Optional<PositionReviewLogEntity> findTopBySymbolOrderByIdDesc(String symbol);
}
