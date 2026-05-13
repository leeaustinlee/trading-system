package com.austin.trading.repository;

import com.austin.trading.entity.ThemeSignalEvidenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ThemeSignalEvidenceRepository extends JpaRepository<ThemeSignalEvidenceEntity, Long> {
    List<ThemeSignalEvidenceEntity> findBySignalId(Long signalId);
    List<ThemeSignalEvidenceEntity> findByTradingDate(LocalDate tradingDate);
    List<ThemeSignalEvidenceEntity> findByTradingDateAndThemeTag(LocalDate tradingDate, String themeTag);
    long countBySignalId(Long signalId);
    long deleteBySignalId(Long signalId);
}
