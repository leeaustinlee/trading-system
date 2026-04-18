package com.austin.trading.engine;

import com.austin.trading.entity.StockThemeMappingEntity;
import com.austin.trading.entity.ThemeSnapshotEntity;
import com.austin.trading.repository.StockThemeMappingRepository;
import com.austin.trading.repository.ThemeSnapshotRepository;
import com.austin.trading.service.ScoreConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 題材選擇引擎（Phase 1：骨架版）。
 *
 * <p><b>Phase 1（目前）</b>：從 DB 讀取已有的 theme_snapshot，
 * 依 final_theme_score 排序，回傳今日主題材清單。
 * 個股題材對應由 stock_theme_mapping 表提供。</p>
 *
 * <p><b>Phase 2（待實作）</b>：
 * <ol>
 *   <li>Java 由即時報價計算 market_behavior_score（成交量、漲幅、強勢股數）</li>
 *   <li>整合 Claude 回傳的 theme_heat_score / theme_continuation_score</li>
 *   <li>依 scoring.theme_weight_in_java 合併出 final_theme_score</li>
 *   <li>寫入 theme_snapshot，供候選股初篩使用</li>
 * </ol>
 * </p>
 */
@Component
public class ThemeSelectionEngine {

    private static final Logger log = LoggerFactory.getLogger(ThemeSelectionEngine.class);

    private final ThemeSnapshotRepository themeSnapshotRepository;
    private final StockThemeMappingRepository stockThemeMappingRepository;
    private final ScoreConfigService config;

    public ThemeSelectionEngine(
            ThemeSnapshotRepository themeSnapshotRepository,
            StockThemeMappingRepository stockThemeMappingRepository,
            ScoreConfigService config
    ) {
        this.themeSnapshotRepository = themeSnapshotRepository;
        this.stockThemeMappingRepository = stockThemeMappingRepository;
        this.config = config;
    }

    /**
     * 取得今日排名前 N 的題材快照（依 final_theme_score 降序）。
     */
    public List<ThemeSnapshotEntity> getRankedThemes(LocalDate tradingDate) {
        return themeSnapshotRepository.findByTradingDateOrderByFinalThemeScoreDesc(tradingDate);
    }

    /**
     * 查詢指定股票今日最強題材標籤（final_theme_score 最高的那個）。
     *
     * @return Optional.empty() 若該股無題材對應
     */
    public Optional<String> getLeadingThemeForStock(String symbol, LocalDate tradingDate) {
        List<StockThemeMappingEntity> mappings = stockThemeMappingRepository
                .findBySymbolAndIsActiveTrue(symbol);
        if (mappings.isEmpty()) return Optional.empty();

        // 嘗試找今日快照中排名最高的
        List<ThemeSnapshotEntity> snapshots = themeSnapshotRepository
                .findByTradingDateOrderByFinalThemeScoreDesc(tradingDate);

        for (ThemeSnapshotEntity snap : snapshots) {
            for (StockThemeMappingEntity mapping : mappings) {
                if (mapping.getThemeTag().equals(snap.getThemeTag())) {
                    return Optional.of(mapping.getThemeTag());
                }
            }
        }

        // 無快照時，直接返回第一個 active mapping
        return Optional.of(mappings.get(0).getThemeTag());
    }

    /**
     * 計算單檔股票的「題材加乘分」（0~1 之間的係數，與 java_structure_score 相乘）。
     * Phase 1：若有題材快照且 final_theme_score > 0，按比例給加乘；否則 1.0。
     */
    public BigDecimal getThemeMultiplier(String symbol, LocalDate tradingDate) {
        Optional<String> leadingTheme = getLeadingThemeForStock(symbol, tradingDate);
        if (leadingTheme.isEmpty()) return BigDecimal.ONE;

        return themeSnapshotRepository
                .findByTradingDateAndThemeTag(tradingDate, leadingTheme.get())
                .map(snap -> {
                    if (snap.getFinalThemeScore() == null) return BigDecimal.ONE;
                    // 將 0~10 分的題材分轉換為 0.8~1.2 的係數
                    BigDecimal normalized = snap.getFinalThemeScore()
                            .divide(new BigDecimal("10"), 4, RoundingMode.HALF_UP);
                    return new BigDecimal("0.8").add(normalized.multiply(new BigDecimal("0.4")));
                })
                .orElse(BigDecimal.ONE);
    }

    // TODO Phase 2: computeMarketBehaviorScore(List<StockQuote> quotes, String themeTag)
    // TODO Phase 2: mergeClaudeThemeScores(LocalDate date, Map<String, BigDecimal> claudeHeatScores)
}
