package com.austin.trading.engine;

import com.austin.trading.engine.TradeReviewEngine.*;
import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TradeReviewEngineTests {

    private ScoreConfigService config;
    private TradeReviewEngine engine;

    @BeforeEach
    void setUp() {
        config = mock(ScoreConfigService.class);
        when(config.getDecimal(anyString(), any(BigDecimal.class))).thenAnswer(i -> i.getArgument(1));
        when(config.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(config.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        engine = new TradeReviewEngine(config);
    }

    @Test
    void goodEntryGoodExit_shouldGradeA() {
        var input = buildInput(b -> {
            b.pnlPct = bd("8.0");
            b.mfePct = bd("10.0");
            b.holdingDays = 5;
            b.consecutiveStrongDays = 3;
        });
        var result = engine.evaluate(input);
        assertThat(result.reviewGrade()).isEqualTo("A");
        assertThat(result.primaryTag()).isEqualTo("GOOD_ENTRY_GOOD_EXIT");
        assertThat(result.strengths()).isNotEmpty();
    }

    @Test
    void chasedTooHigh_shouldGradeD() {
        var input = buildInput(b -> {
            b.pnlPct = bd("-4.0");
            b.wasExtended = true;
            b.mfePct = bd("1.0");
            b.maePct = bd("-5.0");
        });
        var result = engine.evaluate(input);
        assertThat(result.reviewGrade()).isEqualTo("D");
        assertThat(result.primaryTag()).isEqualTo("CHASED_TOO_HIGH");
        assertThat(result.weaknesses()).isNotEmpty();
    }

    @Test
    void failedBreakout_shouldGradeD() {
        var input = buildInput(b -> {
            b.pnlPct = bd("-3.0");
            b.wasFailedBreakout = true;
        });
        var result = engine.evaluate(input);
        assertThat(result.reviewGrade()).isEqualTo("D");
        assertThat(result.primaryTag()).isEqualTo("FAILED_BREAKOUT_ENTRY");
    }

    @Test
    void heldTooLong_shouldGradeC() {
        var input = buildInput(b -> {
            b.pnlPct = bd("-1.0");
            b.holdingDays = 15;
            b.mfePct = bd("2.0");
            b.maePct = bd("-3.0");
        });
        var result = engine.evaluate(input);
        assertThat(result.primaryTag()).isEqualTo("HELD_TOO_LONG");
    }

    @Test
    void profitGiveBack_shouldTagCorrectly() {
        var input = buildInput(b -> {
            b.pnlPct = bd("2.0");       // 最終獲利 2%
            b.mfePct = bd("10.0");      // MFE 10%，回吐 (10-2)/10=80% > 50%
            b.holdingDays = 5;
        });
        var result = engine.evaluate(input);
        assertThat(result.primaryTag()).isEqualTo("STRONG_HOLD_BUT_GAVE_BACK_PROFIT");
        assertThat(result.weaknesses()).anyMatch(w -> w.contains("回吐"));
    }

    @Test
    void marketCondition_shouldPassThrough() {
        var input = buildInput(b -> {
            b.pnlPct = bd("6.0");
            b.mfePct = bd("7.0");
            b.marketCondition = MarketCondition.BEAR;
        });
        var result = engine.evaluate(input);
        assertThat(result.marketCondition()).isEqualTo(MarketCondition.BEAR);
        assertThat(result.aiSummary()).contains("BEAR");
    }

    // ── Helper ─────────────────────────────────────────────────────────────

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private TradeReviewInput buildInput(java.util.function.Consumer<InputBuilder> customizer) {
        InputBuilder b = new InputBuilder();
        customizer.accept(b);
        return new TradeReviewInput(
                b.symbol, b.entryPrice, b.exitPrice, b.pnlPct, b.holdingDays,
                b.mfePct, b.maePct, b.entryReason, b.exitReason,
                b.finalRankScore, b.javaScore, b.claudeScore,
                b.themeRank, b.finalThemeScore,
                b.wasExtended, b.consecutiveStrongDays,
                b.watchlistStatus, b.marketGrade,
                b.originalStopLoss, b.trailingStopAtExit,
                b.wasFailedBreakout, b.wasWeakTheme, b.marketCondition);
    }

    static class InputBuilder {
        String symbol = "2330";
        BigDecimal entryPrice = bd("100");
        BigDecimal exitPrice = bd("106");
        BigDecimal pnlPct = bd("6.0");
        int holdingDays = 5;
        BigDecimal mfePct = bd("7.0");
        BigDecimal maePct = bd("-1.0");
        String entryReason = "BREAKOUT";
        String exitReason = "TAKE_PROFIT";
        BigDecimal finalRankScore = bd("9.0");
        BigDecimal javaScore = bd("8.5");
        BigDecimal claudeScore = bd("8.0");
        Integer themeRank = 1;
        BigDecimal finalThemeScore = bd("8.0");
        boolean wasExtended = false;
        int consecutiveStrongDays = 2;
        String watchlistStatus = "READY";
        String marketGrade = "A";
        BigDecimal originalStopLoss = bd("94");
        BigDecimal trailingStopAtExit = bd("98");
        boolean wasFailedBreakout = false;
        boolean wasWeakTheme = false;
        MarketCondition marketCondition = MarketCondition.BULL;
    }
}
