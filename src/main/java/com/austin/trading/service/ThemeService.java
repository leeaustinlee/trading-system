package com.austin.trading.service;

import com.austin.trading.dto.response.StockThemeMappingResponse;
import com.austin.trading.dto.response.ThemeSnapshotResponse;
import com.austin.trading.entity.StockThemeMappingEntity;
import com.austin.trading.entity.ThemeSnapshotEntity;
import com.austin.trading.repository.StockThemeMappingRepository;
import com.austin.trading.repository.ThemeSnapshotRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class ThemeService {

    private final ThemeSnapshotRepository    snapshotRepo;
    private final StockThemeMappingRepository mappingRepo;

    public ThemeService(ThemeSnapshotRepository snapshotRepo,
                        StockThemeMappingRepository mappingRepo) {
        this.snapshotRepo = snapshotRepo;
        this.mappingRepo  = mappingRepo;
    }

    // ── 快照 ─────────────────────────────────────────────────────────────────

    public List<ThemeSnapshotResponse> getSnapshotsByDate(LocalDate date) {
        return snapshotRepo.findByTradingDateOrderByFinalThemeScoreDesc(date)
                .stream().map(this::toSnapshotResponse).toList();
    }

    // ── 對應 ─────────────────────────────────────────────────────────────────

    public List<StockThemeMappingResponse> getMappingsBySymbol(String symbol) {
        return mappingRepo.findBySymbolAndIsActiveTrue(symbol)
                .stream().map(this::toMappingResponse).toList();
    }

    public List<StockThemeMappingResponse> getMappingsByTheme(String themeTag) {
        return mappingRepo.findByThemeTagAndIsActiveTrue(themeTag)
                .stream().map(this::toMappingResponse).toList();
    }

    public List<StockThemeMappingResponse> getAllActiveMappings() {
        return mappingRepo.findAllByOrderBySymbolAscThemeTagAsc().stream()
                .filter(e -> Boolean.TRUE.equals(e.getIsActive()))
                .map(this::toMappingResponse).toList();
    }

    // ── 新增對應 ─────────────────────────────────────────────────────────────

    public StockThemeMappingResponse addMapping(String symbol, String stockName,
                                                String themeTag, String source) {
        StockThemeMappingEntity entity = new StockThemeMappingEntity();
        entity.setSymbol(symbol);
        entity.setStockName(stockName);
        entity.setThemeTag(themeTag);
        entity.setSource(source != null ? source : "MANUAL");
        entity.setIsActive(true);
        return toMappingResponse(mappingRepo.save(entity));
    }

    // ── 轉換 ─────────────────────────────────────────────────────────────────

    private ThemeSnapshotResponse toSnapshotResponse(ThemeSnapshotEntity e) {
        return new ThemeSnapshotResponse(
                e.getId(), e.getTradingDate(), e.getThemeTag(), e.getThemeCategory(),
                e.getMarketBehaviorScore(), e.getTotalTurnover(), e.getAvgGainPct(),
                e.getStrongStockCount(), e.getLeadingStockSymbol(),
                e.getThemeHeatScore(), e.getThemeContinuationScore(),
                e.getDriverType(), e.getRiskSummary(),
                e.getFinalThemeScore(), e.getRankingOrder()
        );
    }

    private StockThemeMappingResponse toMappingResponse(StockThemeMappingEntity e) {
        return new StockThemeMappingResponse(
                e.getId(), e.getSymbol(), e.getStockName(),
                e.getThemeTag(), e.getSubTheme(), e.getThemeCategory(),
                e.getSource(), e.getConfidence(), e.getIsActive(),
                e.getCreatedAt(), e.getUpdatedAt()
        );
    }
}
