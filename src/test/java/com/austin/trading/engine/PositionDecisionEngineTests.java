package com.austin.trading.engine;

import com.austin.trading.engine.PositionDecisionEngine.*;
import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PositionDecisionEngineTests {

    private ScoreConfigService config;
    private PositionDecisionEngine engine;

    @BeforeEach
    void setUp() {
        config = mock(ScoreConfigService.class);
        // defaults
        when(config.getDecimal(anyString(), any(BigDecimal.class))).thenAnswer(i -> i.getArgument(1));
        when(config.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(config.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        engine = new PositionDecisionEngine(config);
    }

    @Test
    void stopLoss_triggered_shouldExit() {
        var input = buildInput(b -> {
            b.entryPrice = bd("100");
            b.currentPrice = bd("93");         // 跌破停損
            b.currentStopLoss = bd("94");
            b.unrealizedPnlPct = bd("-7");
        });
        var result = engine.evaluate(input);
        assertThat(result.status()).isEqualTo(PositionStatus.EXIT);
        assertThat(result.reason()).contains("停損");
    }

    @Test
    void smallProfit_strongTheme_shouldBeStrong() {
        var input = buildInput(b -> {
            b.entryPrice = bd("100");
            b.currentPrice = bd("102");
            b.unrealizedPnlPct = bd("2");      // < breakeven_pct(3%) 不觸發 TRAIL_UP
            b.momentumStrong = true;
            b.themeRank = 1;
            b.finalThemeScore = bd("8.0");
        });
        var result = engine.evaluate(input);
        assertThat(result.status()).isEqualTo(PositionStatus.STRONG);
    }

    @Test
    void weakTheme_weakPrice_shouldWeaken() {
        var input = buildInput(b -> {
            b.entryPrice = bd("100");
            b.currentPrice = bd("101");
            b.unrealizedPnlPct = bd("1");
            b.finalThemeScore = bd("5.0");     // 低於 6.0 門檻
        });
        var result = engine.evaluate(input);
        assertThat(result.status()).isEqualTo(PositionStatus.WEAKEN);
        assertThat(result.reason()).contains("題材分");
    }

    @Test
    void profitReachesThreshold_shouldTrailUp() {
        var input = buildInput(b -> {
            b.entryPrice = bd("100");
            b.currentPrice = bd("106");
            b.unrealizedPnlPct = bd("6");      // >= 5% first trail
            b.momentumStrong = true;
            b.themeRank = 1;
            b.finalThemeScore = bd("8.0");
            b.dayLow = bd("104");
        });
        var result = engine.evaluate(input);
        assertThat(result.status()).isEqualTo(PositionStatus.TRAIL_UP);
        assertThat(result.trailingAction()).isEqualTo(TrailingAction.MOVE_TO_FIRST);
        assertThat(result.suggestedStopLoss()).isNotNull();
    }

    @Test
    void staleDays_noMomentum_shouldExit() {
        var input = buildInput(b -> {
            b.entryPrice = bd("100");
            b.currentPrice = bd("101");
            b.sessionHighPrice = bd("101");   // 無明顯回撤
            b.unrealizedPnlPct = bd("1");      // < 3%
            b.holdingDays = 8;                  // >= stale_days_without_momentum (7)
            b.momentumStrong = false;
        });
        var result = engine.evaluate(input);
        assertThat(result.status()).isEqualTo(PositionStatus.EXIT);
        assertThat(result.reason()).contains("無動能");
    }

    @Test
    void extremeExtended_weak_shouldExit() {
        var input = buildInput(b -> {
            b.entryPrice = bd("100");
            b.currentPrice = bd("115");
            b.unrealizedPnlPct = bd("15");
            b.extendedLevel = ExtendedLevel.EXTREME;
            b.momentumStrong = false;
        });
        var result = engine.evaluate(input);
        assertThat(result.status()).isEqualTo(PositionStatus.EXIT);
        assertThat(result.reason()).contains("極度延伸");
    }

    @Test
    void mildExtended_profit_shouldHoldNotStrong() {
        var input = buildInput(b -> {
            b.entryPrice = bd("100");
            b.currentPrice = bd("102");
            b.unrealizedPnlPct = bd("2");
            b.extendedLevel = ExtendedLevel.MILD;
            b.momentumStrong = true;
            b.themeRank = 1;
            b.finalThemeScore = bd("8.0");
        });
        var result = engine.evaluate(input);
        assertThat(result.status()).isEqualTo(PositionStatus.HOLD);
        assertThat(result.reason()).contains("MILD 延伸");
    }

    @Test
    void drawdownFromHigh_exceedsExitThreshold_shouldExit() {
        var input = buildInput(b -> {
            b.entryPrice = bd("100");
            b.currentPrice = bd("95");        // 現價
            b.sessionHighPrice = bd("108");   // 持倉期間最高 108，回撤 (108-95)/108 = 12%
            b.unrealizedPnlPct = bd("-5");
        });
        var result = engine.evaluate(input);
        assertThat(result.status()).isEqualTo(PositionStatus.EXIT);
        assertThat(result.reason()).contains("回撤");
    }

    @Test
    void drawdownFromHigh_weakenThreshold_noMomentum_shouldWeaken() {
        var input = buildInput(b -> {
            b.entryPrice = bd("100");
            b.currentPrice = bd("104");
            b.sessionHighPrice = bd("108");   // 回撤 (108-104)/108 ≈ 3.7%
            b.unrealizedPnlPct = bd("4");
            b.momentumStrong = false;
        });
        var result = engine.evaluate(input);
        assertThat(result.status()).isEqualTo(PositionStatus.WEAKEN);
        assertThat(result.reason()).contains("回撤");
    }

    @Test
    void failedBreakout_withLoss_shouldExit() {
        var input = buildInput(b -> {
            b.entryPrice = bd("100");
            b.currentPrice = bd("99");
            b.sessionHighPrice = bd("100");  // 無明顯回撤
            b.unrealizedPnlPct = bd("-1");
            b.failedBreakout = true;
        });
        var result = engine.evaluate(input);
        assertThat(result.status()).isEqualTo(PositionStatus.EXIT);
        assertThat(result.reason()).contains("假突破");
    }

    // ── Helper ─────────────────────────────────────────────────────────────

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private PositionDecisionInput buildInput(java.util.function.Consumer<InputBuilder> customizer) {
        InputBuilder b = new InputBuilder();
        customizer.accept(b);
        return new PositionDecisionInput(
                b.symbol, b.entryPrice, b.currentStopLoss, b.takeProfit1, b.takeProfit2,
                b.trailingStopPrice, b.side, b.holdingDays,
                b.currentPrice, b.dayHigh, b.dayLow, b.prevClose,
                b.sessionHighPrice,
                b.marketGrade, b.themeRank, b.finalThemeScore, b.unrealizedPnlPct,
                b.extendedLevel, b.volumeWeakening, b.failedBreakout, b.momentumStrong,
                b.nearResistance, b.madeNewHighRecently);
    }

    static class InputBuilder {
        String symbol = "2330";
        BigDecimal entryPrice = bd("100");
        BigDecimal currentStopLoss = bd("94");
        BigDecimal takeProfit1 = bd("108");
        BigDecimal takeProfit2 = bd("113");
        BigDecimal trailingStopPrice = null;
        String side = "LONG";
        int holdingDays = 3;
        BigDecimal currentPrice = bd("102");
        BigDecimal dayHigh = bd("103");
        BigDecimal dayLow = bd("101");
        BigDecimal prevClose = bd("100");
        BigDecimal sessionHighPrice = bd("105");
        String marketGrade = "A";
        Integer themeRank = 1;
        BigDecimal finalThemeScore = bd("8.0");
        BigDecimal unrealizedPnlPct = bd("2");
        ExtendedLevel extendedLevel = ExtendedLevel.NONE;
        boolean volumeWeakening = false;
        boolean failedBreakout = false;
        boolean momentumStrong = true;
        boolean nearResistance = false;
        boolean madeNewHighRecently = false;
    }
}
