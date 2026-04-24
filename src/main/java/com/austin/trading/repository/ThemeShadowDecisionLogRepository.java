package com.austin.trading.repository;

import com.austin.trading.entity.ThemeShadowDecisionLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * v2 Theme Engine Shadow Mode log repository（PR1）。
 *
 * <p>PR5 才會真正寫入。PR1 只定義查詢介面供後續使用。</p>
 */
public interface ThemeShadowDecisionLogRepository
        extends JpaRepository<ThemeShadowDecisionLogEntity, Long> {

    List<ThemeShadowDecisionLogEntity> findByTradingDate(LocalDate tradingDate);

    Optional<ThemeShadowDecisionLogEntity>
        findByTradingDateAndSymbol(LocalDate tradingDate, String symbol);

    List<ThemeShadowDecisionLogEntity>
        findByTradingDateAndDecisionDiffType(LocalDate tradingDate, String decisionDiffType);
}
