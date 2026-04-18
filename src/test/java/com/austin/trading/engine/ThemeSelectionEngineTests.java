package com.austin.trading.engine;

import com.austin.trading.engine.ThemeSelectionEngine.StockQuoteInput;
import com.austin.trading.repository.StockThemeMappingRepository;
import com.austin.trading.repository.ThemeSnapshotRepository;
import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ThemeSelectionEngineTests {

    private ThemeSelectionEngine engine;
    private ScoreConfigService config;

    @BeforeEach
    void setUp() {
        config = mock(ScoreConfigService.class);
        // 預設回傳設定值
        when(config.getDecimal(eq("theme.weight.market_behavior"), any())).thenReturn(new BigDecimal("0.50"));
        when(config.getDecimal(eq("theme.weight.heat"),            any())).thenReturn(new BigDecimal("0.30"));
        when(config.getDecimal(eq("theme.weight.continuation"),    any())).thenReturn(new BigDecimal("0.20"));
        when(config.getDecimal(eq("theme.strong_stock_threshold_pct"), any())).thenReturn(new BigDecimal("2.0"));

        engine = new ThemeSelectionEngine(
                mock(ThemeSnapshotRepository.class),
                mock(StockThemeMappingRepository.class),
                config
        );
    }

    @Test
    void emptyQuotesShouldReturnZero() {
        BigDecimal score = engine.computeMarketBehaviorScore(List.of());
        assertEquals(0, score.compareTo(BigDecimal.ZERO));
    }

    @Test
    void allStocksStrongShouldScoreHigh() {
        // 3 檔均漲 4%+，全部超過 threshold(2%)
        List<StockQuoteInput> quotes = List.of(
                new StockQuoteInput("2330", 4.5, 5000L, 1000.0),
                new StockQuoteInput("2303", 4.2, 3000L, 200.0),
                new StockQuoteInput("2454", 3.8, 2000L, 300.0)
        );
        BigDecimal score = engine.computeMarketBehaviorScore(quotes);
        // avgGain ~4.2% → avgGainScore=4, strongRatio=1.0→3, allPositive→+1, allAbove1→+1 = 9
        assertTrue(score.doubleValue() >= 8.0, "強勢題材分應 >= 8，實際：" + score);
    }

    @Test
    void weakThemeShouldScoreLow() {
        // 均跌
        List<StockQuoteInput> quotes = List.of(
                new StockQuoteInput("1234", -1.5, 1000L, 50.0),
                new StockQuoteInput("5678", -2.0, 800L,  80.0)
        );
        BigDecimal score = engine.computeMarketBehaviorScore(quotes);
        // avgGain < 0 → avgGainScore=0, strongRatio=0→0, no bonus = 0
        assertEquals(0, score.compareTo(BigDecimal.ZERO), "下跌題材分應為 0，實際：" + score);
    }

    @Test
    void mixedThemeShouldScoreMid() {
        // 一漲一跌
        List<StockQuoteInput> quotes = List.of(
                new StockQuoteInput("A", 3.0, 2000L, 100.0),
                new StockQuoteInput("B", -1.0, 1000L, 50.0)
        );
        BigDecimal score = engine.computeMarketBehaviorScore(quotes);
        // avgGain=1.0 → 2, strongRatio=0.5 → 2, not allPositive → 0 = 4
        assertEquals(0, score.compareTo(new BigDecimal("4.000")), "混合題材應得 4，實際：" + score);
    }

    @Test
    void finalScoreShouldWeightComponentsCorrectly() {
        // market=8, heat=6, continuation=4
        // final = 8*0.5 + 6*0.3 + 4*0.2 = 4 + 1.8 + 0.8 = 6.6
        BigDecimal finalScore = engine.computeFinalThemeScore(
                new BigDecimal("8"),
                new BigDecimal("6"),
                new BigDecimal("4")
        );
        assertEquals(0, finalScore.compareTo(new BigDecimal("6.600")),
                "加權分應為 6.600，實際：" + finalScore);
    }

    @Test
    void finalScoreMissingClaudeShouldUseMarketBehaviorOnly() {
        // 只有 market_behavior，heat/continuation 為 null
        // 正規化後應等於 marketBehaviorScore 本身
        BigDecimal finalScore = engine.computeFinalThemeScore(
                new BigDecimal("7.5"),
                null,
                null
        );
        assertEquals(0, finalScore.compareTo(new BigDecimal("7.500")),
                "只有 market 分時應直接等於 market 分，實際：" + finalScore);
    }
}
