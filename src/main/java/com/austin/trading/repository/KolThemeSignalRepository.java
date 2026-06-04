package com.austin.trading.repository;

import com.austin.trading.entity.KolThemeSignalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface KolThemeSignalRepository extends JpaRepository<KolThemeSignalEntity, Long> {
    Optional<KolThemeSignalEntity> findByContentHash(String contentHash);
    List<KolThemeSignalEntity> findByTradingDateOrderByCreatedAtDesc(LocalDate tradingDate);
    long countByTradingDate(LocalDate tradingDate);
    long countByTradingDateBetween(LocalDate startDate, LocalDate endDate);

    @Query("select max(k.tradingDate) from KolThemeSignalEntity k")
    LocalDate findLatestTradingDate();
}
