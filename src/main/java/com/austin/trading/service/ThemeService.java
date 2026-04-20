package com.austin.trading.service;

import com.austin.trading.dto.request.ClaudeThemeScoreRequest;
import com.austin.trading.dto.response.StockThemeMappingResponse;
import com.austin.trading.dto.response.ThemeSnapshotResponse;
import com.austin.trading.engine.ThemeSelectionEngine;
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
    private final ThemeSelectionEngine       themeEngine;

    public ThemeService(ThemeSnapshotRepository snapshotRepo,
                        StockThemeMappingRepository mappingRepo,
                        ThemeSelectionEngine themeEngine) {
        this.snapshotRepo = snapshotRepo;
        this.mappingRepo  = mappingRepo;
        this.themeEngine  = themeEngine;
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

    // ── Claude 評分回填 ───────────────────────────────────────────────────────

    /**
     * Claude 題材評分回填，並重算 final_theme_score。
     */
    public ThemeSnapshotResponse mergeClaudeScores(String themeTag, ClaudeThemeScoreRequest req) {
        LocalDate date = req.tradingDate() != null ? req.tradingDate() : LocalDate.now();
        ThemeSnapshotEntity saved = themeEngine.mergeClaudeThemeScores(
                date, themeTag,
                req.themeHeatScore(), req.themeContinuationScore(),
                req.driverType(), req.riskSummary()
        );
        return toSnapshotResponse(saved);
    }

    // ── 新增對應 ─────────────────────────────────────────────────────────────

    public StockThemeMappingResponse addMapping(String symbol, String stockName,
                                                String themeTag, String source) {
        StockThemeMappingEntity entity = mappingRepo.findBySymbolAndThemeTag(symbol, themeTag)
                .orElseGet(StockThemeMappingEntity::new);
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
