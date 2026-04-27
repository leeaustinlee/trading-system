package com.austin.trading.repository;

import com.austin.trading.entity.PaperTradeSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaperTradeSnapshotRepository
        extends JpaRepository<PaperTradeSnapshotEntity, Long> {

    /** All snapshots for a paper trade in chronological order (ENTRY first, then EXIT). */
    List<PaperTradeSnapshotEntity> findByPaperTradeIdOrderByCapturedAtAsc(Long paperTradeId);

    /** Idempotency check: most recent snapshot of a given type for a paper trade. */
    Optional<PaperTradeSnapshotEntity>
        findTopByPaperTradeIdAndSnapshotTypeOrderByCapturedAtDesc(Long paperTradeId, String snapshotType);

    /** Count snapshots of a given type for a paper trade (used to detect missing snapshots). */
    long countByPaperTradeIdAndSnapshotType(Long paperTradeId, String snapshotType);
}
