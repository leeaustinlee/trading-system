package com.austin.trading.repository;

import com.austin.trading.entity.StopWashoutOutcomeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StopWashoutOutcomeRepository extends JpaRepository<StopWashoutOutcomeEntity, Long> {
    boolean existsByStructuralExitLogId(Long structuralExitLogId);
    boolean existsByStructuralExitLogIdAndOutcomeBasis(Long structuralExitLogId, String outcomeBasis);
    long countByOutcomeLabel(String outcomeLabel);
    long countByOutcomeBasisAndOutcomeLabel(String outcomeBasis, String outcomeLabel);
    long countByOutcomeBasis(String outcomeBasis);
    long countBySignalTierInAndOutcomeLabel(java.util.List<String> signalTiers, String outcomeLabel);
    long countByOutcomeBasisAndSignalTierInAndOutcomeLabel(String outcomeBasis, java.util.List<String> signalTiers, String outcomeLabel);
    long countBySignalTierIn(java.util.List<String> signalTiers);
    long countByOutcomeBasisAndSignalTierIn(String outcomeBasis, java.util.List<String> signalTiers);
}
