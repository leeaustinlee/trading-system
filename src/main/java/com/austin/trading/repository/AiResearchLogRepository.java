package com.austin.trading.repository;

import com.austin.trading.entity.AiResearchLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AiResearchLogRepository extends JpaRepository<AiResearchLogEntity, Long> {

    List<AiResearchLogEntity> findByTradingDateOrderByCreatedAtDesc(LocalDate tradingDate);

    List<AiResearchLogEntity> findByTradingDateAndResearchTypeOrderByCreatedAtDesc(
            LocalDate tradingDate, String researchType);

    Optional<AiResearchLogEntity> findTopByResearchTypeAndSymbolOrderByCreatedAtDesc(
            String researchType, String symbol);

    Optional<AiResearchLogEntity> findTopByTradingDateAndResearchTypeOrderByCreatedAtDesc(
            LocalDate tradingDate, String researchType);
}
