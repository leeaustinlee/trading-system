package com.austin.trading.repository;

import com.austin.trading.entity.DecisionSnapshotLedgerEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DecisionSnapshotLedgerRepository extends JpaRepository<DecisionSnapshotLedgerEntity, Long> {

    List<DecisionSnapshotLedgerEntity> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    List<DecisionSnapshotLedgerEntity> findByFinalDecisionIdOrderByCreatedAtDescIdDesc(Long finalDecisionId);
}
