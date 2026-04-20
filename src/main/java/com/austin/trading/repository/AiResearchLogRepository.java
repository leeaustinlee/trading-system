package com.austin.trading.repository;

import com.austin.trading.entity.AiResearchLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AiResearchLogRepository extends JpaRepository<AiResearchLogEntity, Long> {

    // 主排序 created_at DESC；同秒插入時用 id DESC 作 tie-breaker，保證最新一定在最上
    List<AiResearchLogEntity> findByTradingDateOrderByCreatedAtDescIdDesc(LocalDate tradingDate);

    List<AiResearchLogEntity> findByTradingDateAndResearchTypeOrderByCreatedAtDescIdDesc(
            LocalDate tradingDate, String researchType);

    Optional<AiResearchLogEntity> findTopByResearchTypeAndSymbolOrderByCreatedAtDescIdDesc(
            String researchType, String symbol);

    Optional<AiResearchLogEntity> findTopByTradingDateAndResearchTypeOrderByCreatedAtDescIdDesc(
            LocalDate tradingDate, String researchType);
}
