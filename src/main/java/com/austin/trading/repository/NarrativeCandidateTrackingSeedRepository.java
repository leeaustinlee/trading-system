package com.austin.trading.repository;

import com.austin.trading.entity.NarrativeCandidateTrackingSeedEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface NarrativeCandidateTrackingSeedRepository extends JpaRepository<NarrativeCandidateTrackingSeedEntity, Long> {
    List<NarrativeCandidateTrackingSeedEntity> findByDecisionDateOrderByShadowDeltaDesc(LocalDate decisionDate);
    long deleteByDecisionDate(LocalDate decisionDate);
}
