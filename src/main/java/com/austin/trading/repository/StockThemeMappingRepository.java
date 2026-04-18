package com.austin.trading.repository;

import com.austin.trading.entity.StockThemeMappingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StockThemeMappingRepository extends JpaRepository<StockThemeMappingEntity, Long> {

    List<StockThemeMappingEntity> findBySymbolAndIsActiveTrue(String symbol);

    List<StockThemeMappingEntity> findByThemeTagAndIsActiveTrue(String themeTag);

    List<StockThemeMappingEntity> findByThemeCategoryAndIsActiveTrue(String themeCategory);

    Optional<StockThemeMappingEntity> findBySymbolAndThemeTag(String symbol, String themeTag);

    List<StockThemeMappingEntity> findAllByOrderBySymbolAscThemeTagAsc();
}
