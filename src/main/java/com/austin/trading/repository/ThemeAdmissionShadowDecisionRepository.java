package com.austin.trading.repository;

import com.austin.trading.domain.enums.ThemeAdmissionShadowAction;
import com.austin.trading.entity.ThemeAdmissionShadowDecisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Repository for shadow-only theme admission decisions. */
public interface ThemeAdmissionShadowDecisionRepository extends JpaRepository<ThemeAdmissionShadowDecisionEntity, Long> {
    List<ThemeAdmissionShadowDecisionEntity> findByTradingDate(LocalDate tradingDate);
    List<ThemeAdmissionShadowDecisionEntity> findByTradingDateAndThemeTag(LocalDate tradingDate, String themeTag);
    List<ThemeAdmissionShadowDecisionEntity> findByTradingDateAndShadowAction(LocalDate tradingDate, ThemeAdmissionShadowAction shadowAction);
    Optional<ThemeAdmissionShadowDecisionEntity> findByTradingDateAndSymbolAndThemeTag(LocalDate tradingDate, String symbol, String themeTag);
    Optional<ThemeAdmissionShadowDecisionEntity> findByTradingDateAndSymbolAndThemeTagAndSignalId(LocalDate tradingDate, String symbol, String themeTag, Long signalId);
}
