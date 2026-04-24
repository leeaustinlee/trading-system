package com.austin.trading.engine;

import com.austin.trading.domain.enums.PositionAction;
import com.austin.trading.domain.enums.PositionSizeLevel;
import com.austin.trading.dto.internal.PositionManagementInput;
import com.austin.trading.dto.internal.PositionManagementInput.SwitchCandidate;
import com.austin.trading.dto.internal.PositionManagementResult;
import com.austin.trading.engine.PositionDecisionEngine.PositionStatus;
import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v2.10 PositionManagementEngine 8 cases。
 * Case 8（通知節流）在 integration / LineTemplateService 層驗，此處不涵蓋。
 */
class PositionManagementEngineTests {

    private ScoreConfigService config;
    private PositionManagementEngine engine;

    @BeforeEach
    void setUp() {
        config = mock(ScoreConfigService.class);
        when(config.getDecimal(eq("position.mgmt.add_min_pnl_pct"), any())).thenReturn(new BigDecimal("2.0"));
        when(config.getDecimal(eq("position.mgmt.add_min_volume_ratio"), any())).thenReturn(new BigDecimal("1.2"));
        when(config.getDecimal(eq("position.mgmt.add_near_high_factor"), any())).thenReturn(new BigDecimal("0.995"));
        when(config.getDecimal(eq("position.mgmt.reduce_low_volume_ratio"), any())).thenReturn(new BigDecimal("0.8"));
        when(config.getDecimal(eq("position.mgmt.reduce_giveback_pct"), any())).thenReturn(new BigDecimal("40"));
        when(config.getDecimal(eq("position.mgmt.switch_score_gap"), any())).thenReturn(new BigDecimal("1.5"));
        engine = new PositionManagementEngine(config);
    }

    /** Case 1：續強加碼 — 浮盈 ≥2%、站上 VWAP、量放大、接近日高、BULL。 */
    @Test
    void case1_strongContinuation_addsSize() {
        PositionManagementInput in = new PositionManagementInput(
                "2454", PositionStatus.STRONG, "BULL_TREND",
                /*current*/ new BigDecimal("2200"),
                /*entry*/   new BigDecimal("2150"),
                /*stop*/    new BigDecimal("2080"),
                /*trail*/   new BigDecimal("2160"),
                /*sessHi*/  new BigDecimal("2210"),       // 2200 >= 2210*0.995 = 2199 ✓
                /*peak*/    new BigDecimal("3.0"),
                /*vwap*/    new BigDecimal("2180"),
                /*volRatio*/new BigDecimal("1.5"),
                PositionSizeLevel.NORMAL,
                0, 0,
                new BigDecimal("8.2"), "SEMI", List.of());
        PositionManagementResult r = engine.evaluate(in);
        assertThat(r.action()).isEqualTo(PositionAction.ADD);
        assertThat(r.reason()).isEqualTo("ADD_STRONG_CONTINUATION");
        assertThat(r.signals()).contains("STRONG_CONTINUATION", "ABOVE_VWAP", "HIGH_VOLUME", "NEAR_HIGH");
    }

    /** Case 2：positionSizeLevel=CORE 其他條件都符合 → 仍 HOLD。 */
    @Test
    void case2_coreSizeLevel_doesNotAdd_fallsToHold() {
        PositionManagementInput in = new PositionManagementInput(
                "2454", PositionStatus.STRONG, "BULL_TREND",
                new BigDecimal("2200"), new BigDecimal("2150"),
                new BigDecimal("2080"), new BigDecimal("2160"),
                new BigDecimal("2210"), new BigDecimal("3.0"),
                new BigDecimal("2180"), new BigDecimal("1.5"),
                PositionSizeLevel.CORE, 0, 0,
                new BigDecimal("8.2"), "SEMI", List.of());
        PositionManagementResult r = engine.evaluate(in);
        assertThat(r.action()).isEqualTo(PositionAction.HOLD);
        assertThat(r.warnings()).contains("SIZE_ALREADY_CORE");
    }

    /** Case 3：跌破 VWAP → REDUCE（未破 stopLoss / trailingStop）。 */
    @Test
    void case3_belowVwap_suggestsReduce() {
        PositionManagementInput in = new PositionManagementInput(
                "2454", PositionStatus.HOLD, "BULL_TREND",
                /*current*/ new BigDecimal("2165"),
                /*entry*/   new BigDecimal("2150"),
                /*stop*/    new BigDecimal("2080"),
                /*trail*/   new BigDecimal("2100"),
                new BigDecimal("2210"), new BigDecimal("3.0"),
                /*vwap*/    new BigDecimal("2180"),       // current 2165 < vwap 2180 ✓
                /*volRatio*/new BigDecimal("1.0"),
                PositionSizeLevel.NORMAL, 0, 0,
                new BigDecimal("7.5"), "SEMI", List.of());
        PositionManagementResult r = engine.evaluate(in);
        assertThat(r.action()).isEqualTo(PositionAction.REDUCE);
        assertThat(r.reason()).isEqualTo("REDUCE_BELOW_VWAP");
        assertThat(r.signals()).contains("BELOW_VWAP");
    }

    /** Case 4：跌破 stopLoss → EXIT。 */
    @Test
    void case4_stopLossHit_exits() {
        PositionManagementInput in = new PositionManagementInput(
                "2454", PositionStatus.HOLD, "BULL_TREND",
                new BigDecimal("2075"), new BigDecimal("2150"),
                new BigDecimal("2080"), new BigDecimal("2100"),
                new BigDecimal("2210"), null,
                null, null,
                PositionSizeLevel.NORMAL, 0, 0,
                new BigDecimal("7.5"), "SEMI", List.of());
        PositionManagementResult r = engine.evaluate(in);
        assertThat(r.action()).isEqualTo(PositionAction.EXIT);
        assertThat(r.reason()).isEqualTo("EXIT_STOP_LOSS");
        assertThat(r.signals()).contains("STOP_LOSS_HIT");
    }

    /** Case 5：跌破 trailingStop（未破 stopLoss）→ EXIT。 */
    @Test
    void case5_trailingStopHit_exits() {
        PositionManagementInput in = new PositionManagementInput(
                "2454", PositionStatus.HOLD, "BULL_TREND",
                /*current*/ new BigDecimal("2098"),
                /*entry*/   new BigDecimal("2150"),
                /*stop*/    new BigDecimal("2080"),       // 未破
                /*trail*/   new BigDecimal("2100"),       // 2098 <= 2100 ✓
                new BigDecimal("2210"), null,
                null, null,
                PositionSizeLevel.NORMAL, 0, 0,
                new BigDecimal("7.5"), "SEMI", List.of());
        PositionManagementResult r = engine.evaluate(in);
        assertThat(r.action()).isEqualTo(PositionAction.EXIT);
        assertThat(r.reason()).isEqualTo("EXIT_TRAILING_STOP");
        assertThat(r.signals()).contains("TRAILING_STOP_HIT");
    }

    /** Case 6：PANIC_VOLATILITY → EXIT（優先於其他路徑）。 */
    @Test
    void case6_panicRegime_exits() {
        PositionManagementInput in = new PositionManagementInput(
                "2454", PositionStatus.HOLD, "PANIC_VOLATILITY",
                new BigDecimal("2200"), new BigDecimal("2150"),
                new BigDecimal("2080"), new BigDecimal("2100"),
                new BigDecimal("2210"), new BigDecimal("3.0"),
                new BigDecimal("2180"), new BigDecimal("1.5"),
                PositionSizeLevel.NORMAL, 0, 0,
                new BigDecimal("8.2"), "SEMI", List.of());
        PositionManagementResult r = engine.evaluate(in);
        assertThat(r.action()).isEqualTo(PositionAction.EXIT);
        assertThat(r.reason()).isEqualTo("EXIT_PANIC");
    }

    /** Case 7：更強新候選且同主題 + scoreGap>=1.5 → SWITCH_HINT。 */
    @Test
    void case7_strongerCandidate_suggestsSwitch() {
        // 持倉 HOLD + 無減碼訊號，但新候選 score 高出 1.8
        SwitchCandidate stronger = new SwitchCandidate(
                "3035", new BigDecimal("9.0"), "SELECT_BUY_NOW", "SEMI", true);
        PositionManagementInput in = new PositionManagementInput(
                "2454", PositionStatus.HOLD, "BULL_TREND",
                new BigDecimal("2200"), new BigDecimal("2150"),
                new BigDecimal("2080"), new BigDecimal("2100"),
                new BigDecimal("2210"), new BigDecimal("3.0"),
                new BigDecimal("2220"),                    // vwap > current → 無 above_vwap → 不 ADD
                new BigDecimal("1.0"),                     // volumeRatio 剛好 >= 0.8 → 無 low_volume reduce
                PositionSizeLevel.NORMAL, 0, 0,
                new BigDecimal("7.2"),                     // current score 7.2
                "SEMI", List.of(stronger));
        PositionManagementResult r = engine.evaluate(in);
        assertThat(r.action()).isEqualTo(PositionAction.SWITCH_HINT);
        assertThat(r.reason()).isEqualTo("NEW_CANDIDATE_STRONGER");
        assertThat(r.trace()).containsKey("switchHint");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> hint = (java.util.Map<String, Object>) r.trace().get("switchHint");
        assertThat(hint.get("switchTo")).isEqualTo("3035");
        assertThat(((BigDecimal) hint.get("scoreGap"))).isEqualByComparingTo(new BigDecimal("1.8"));
    }

    /** Case 8 補充：baselineStatus=EXIT 優先 EXIT，不給 SWITCH_HINT。 */
    @Test
    void case8_baselineExitOverridesSwitchHint() {
        SwitchCandidate stronger = new SwitchCandidate(
                "3035", new BigDecimal("9.0"), "SELECT_BUY_NOW", "SEMI", true);
        // baselineStatus=EXIT + 大量 → EXIT_STRUCTURE_BREAK 觸發
        PositionManagementInput in = new PositionManagementInput(
                "2454", PositionStatus.EXIT, "BULL_TREND",
                new BigDecimal("2200"), new BigDecimal("2150"),
                new BigDecimal("2080"), new BigDecimal("2100"),
                new BigDecimal("2210"), new BigDecimal("3.0"),
                new BigDecimal("2180"), new BigDecimal("1.3"),
                PositionSizeLevel.NORMAL, 0, 0,
                new BigDecimal("7.2"), "SEMI", List.of(stronger));
        PositionManagementResult r = engine.evaluate(in);
        assertThat(r.action()).isEqualTo(PositionAction.EXIT);
        assertThat(r.reason()).isEqualTo("EXIT_STRUCTURE_BREAK");
    }

    /** 額外：VWAP / volumeRatio 缺資料 → 不 ADD，退 HOLD（避免誤殺）。 */
    @Test
    void missingVwap_defaultsToHold() {
        PositionManagementInput in = new PositionManagementInput(
                "2454", PositionStatus.STRONG, "BULL_TREND",
                new BigDecimal("2200"), new BigDecimal("2150"),
                new BigDecimal("2080"), new BigDecimal("2100"),
                new BigDecimal("2210"), null,
                /*vwap*/ null, /*volRatio*/ null,
                PositionSizeLevel.NORMAL, 0, 0,
                new BigDecimal("7.8"), "SEMI", List.of());
        PositionManagementResult r = engine.evaluate(in);
        assertThat(r.action()).isEqualTo(PositionAction.HOLD);
    }
}
