package com.austin.trading.engine;

import com.austin.trading.engine.WatchlistEngine.*;
import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WatchlistEngineTests {

    private ScoreConfigService config;
    private WatchlistEngine engine;

    @BeforeEach
    void setUp() {
        config = mock(ScoreConfigService.class);
        when(config.getDecimal(anyString(), any(BigDecimal.class))).thenAnswer(i -> i.getArgument(1));
        when(config.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(config.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        engine = new WatchlistEngine(config);
    }

    @Test
    void newHighScoreCandidate_shouldAdd() {
        var input = buildInput(b -> {
            b.currentWatchStatus = null;        // 新股
            b.currentScore = bd("7.0");
            b.themeRank = 2;
            b.finalThemeScore = bd("7.5");
        });
        var result = engine.evaluate(input);
        assertThat(result.action()).isEqualTo(WatchlistAction.ADD);
    }

    @Test
    void consecutiveStrong_shouldPromoteReady() {
        var input = buildInput(b -> {
            b.currentWatchStatus = "TRACKING";
            b.currentScore = bd("7.5");
            b.currentConsecutiveStrongDays = 3; // >= 2
            b.themeRank = 1;
            b.finalThemeScore = bd("8.0");
            b.momentumStrong = true;
            b.currentObservationDays = 4;
        });
        var result = engine.evaluate(input);
        assertThat(result.action()).isEqualTo(WatchlistAction.PROMOTE_READY);
    }

    @Test
    void scoreDrop_shouldDrop() {
        var input = buildInput(b -> {
            b.currentWatchStatus = "TRACKING";
            b.currentScore = bd("4.5");         // < 5.0 drop threshold
            b.currentObservationDays = 2;
        });
        var result = engine.evaluate(input);
        assertThat(result.action()).isEqualTo(WatchlistAction.DROP);
    }

    @Test
    void observationTooLong_shouldExpire() {
        var input = buildInput(b -> {
            b.currentWatchStatus = "TRACKING";
            b.currentScore = bd("6.5");
            b.currentObservationDays = 11;      // > 10
        });
        var result = engine.evaluate(input);
        assertThat(result.action()).isEqualTo(WatchlistAction.EXPIRE);
    }

    @Test
    void extended_shouldNotPromoteReady() {
        var input = buildInput(b -> {
            b.currentWatchStatus = "TRACKING";
            b.currentScore = bd("8.0");
            b.currentConsecutiveStrongDays = 5;
            b.themeRank = 1;
            b.finalThemeScore = bd("9.0");
            b.isExtended = true;                // 延伸過頭
            b.momentumStrong = true;
            b.currentObservationDays = 4;
        });
        var result = engine.evaluate(input);
        assertThat(result.action()).isNotEqualTo(WatchlistAction.PROMOTE_READY);
        assertThat(result.action()).isEqualTo(WatchlistAction.KEEP);
    }

    @Test
    void cooldown_shouldNotPromoteReady() {
        var input = buildInput(b -> {
            b.currentWatchStatus = "TRACKING";
            b.currentScore = bd("8.0");
            b.currentConsecutiveStrongDays = 3;
            b.themeRank = 1;
            b.finalThemeScore = bd("8.0");
            b.inCooldown = true;                // 冷卻中
            b.currentObservationDays = 4;
        });
        var result = engine.evaluate(input);
        assertThat(result.action()).isNotEqualTo(WatchlistAction.PROMOTE_READY);
    }

    @Test
    void decayReducesScore_shouldDrop() {
        var input = buildInput(b -> {
            b.currentWatchStatus = "TRACKING";
            b.currentScore = bd("5.5");         // 5.5 - 0.15*(7-3) = 5.5 - 0.6 = 4.9 < 5.0
            b.currentObservationDays = 7;
            b.momentumStrong = false;
        });
        var result = engine.evaluate(input);
        assertThat(result.action()).isEqualTo(WatchlistAction.DROP);
    }

    @Test
    void momentumStrong_decayExempt_shouldKeep() {
        var input = buildInput(b -> {
            b.currentWatchStatus = "TRACKING";
            b.currentScore = bd("5.5");         // 不扣 decay 因為 momentumStrong
            b.currentObservationDays = 7;
            b.momentumStrong = true;
            b.themeRank = 2;
            b.finalThemeScore = bd("7.0");
        });
        var result = engine.evaluate(input);
        assertThat(result.action()).isEqualTo(WatchlistAction.KEEP);
    }

    @Test
    void alreadyHeld_shouldDrop() {
        var input = buildInput(b -> {
            b.currentWatchStatus = "TRACKING";
            b.isAlreadyHeld = true;
        });
        var result = engine.evaluate(input);
        assertThat(result.action()).isEqualTo(WatchlistAction.DROP);
    }

    @Test
    void marketGradeC_shouldBlockAdd() {
        var input = buildInput(b -> {
            b.currentWatchStatus = null;      // 新股
            b.currentScore = bd("8.0");
            b.marketGrade = "C";
        });
        var result = engine.evaluate(input);
        assertThat(result.action()).isEqualTo(WatchlistAction.DROP);
        assertThat(result.reason()).contains("市場等級 C");
    }

    @Test
    void marketGradeC_shouldBlockPromoteReady() {
        var input = buildInput(b -> {
            b.currentWatchStatus = "TRACKING";
            b.currentScore = bd("8.0");
            b.currentConsecutiveStrongDays = 5;
            b.themeRank = 1;
            b.finalThemeScore = bd("9.0");
            b.momentumStrong = true;
            b.currentObservationDays = 4;
            b.marketGrade = "C";
        });
        var result = engine.evaluate(input);
        assertThat(result.action()).isNotEqualTo(WatchlistAction.PROMOTE_READY);
    }

    @Test
    void failedBreakout_shouldDrop() {
        var input = buildInput(b -> {
            b.currentWatchStatus = "TRACKING";
            b.currentScore = bd("7.0");
            b.failedBreakout = true;
            b.currentObservationDays = 3;
        });
        var result = engine.evaluate(input);
        assertThat(result.action()).isEqualTo(WatchlistAction.DROP);
    }

    // ── Helper ─────────────────────────────────────────────────────────────

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private WatchlistEvaluationInput buildInput(java.util.function.Consumer<InputBuilder> customizer) {
        InputBuilder b = new InputBuilder();
        customizer.accept(b);
        return new WatchlistEvaluationInput(
                b.symbol, b.currentScore, b.previousHighestScore,
                b.themeTag, b.themeRank, b.finalThemeScore,
                b.currentObservationDays, b.currentConsecutiveStrongDays,
                b.currentWatchStatus, b.isVetoed, b.isAlreadyHeld, b.isAlreadyEntered,
                b.isExtended, b.momentumStrong, b.failedBreakout, b.inCooldown,
                b.marketGrade);
    }

    static class InputBuilder {
        String symbol = "2330";
        BigDecimal currentScore = bd("7.0");
        BigDecimal previousHighestScore = bd("7.0");
        String themeTag = "AI";
        Integer themeRank = 1;
        BigDecimal finalThemeScore = bd("8.0");
        int currentObservationDays = 1;
        int currentConsecutiveStrongDays = 0;
        String currentWatchStatus = null;
        boolean isVetoed = false;
        boolean isAlreadyHeld = false;
        boolean isAlreadyEntered = false;
        boolean isExtended = false;
        boolean momentumStrong = false;
        boolean failedBreakout = false;
        boolean inCooldown = false;
        String marketGrade = "A";
    }
}
