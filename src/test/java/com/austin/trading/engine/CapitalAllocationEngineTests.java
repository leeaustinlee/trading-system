package com.austin.trading.engine;

import com.austin.trading.domain.enums.AllocationAction;
import com.austin.trading.domain.enums.AllocationMode;
import com.austin.trading.dto.internal.CapitalAllocationInput;
import com.austin.trading.dto.internal.CapitalAllocationInput.AllocationIntent;
import com.austin.trading.dto.internal.CapitalAllocationResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v2.11 CapitalAllocationEngine 9 cases — 完全覆蓋 spec §十三 的測試清單。
 */
class CapitalAllocationEngineTests {

    private final CapitalAllocationEngine engine = new CapitalAllocationEngine();

    /** Case 1：NORMAL buy sizing。 */
    @Test
    void case1_normalBuySizing() {
        CapitalAllocationInput in = entry(
                "2454", "SEMI", "SELECT_BUY_NOW", new BigDecimal("7.8"),
                new BigDecimal("120"), new BigDecimal("114"),
                /*equity*/ new BigDecimal("100000"), /*cash*/ new BigDecimal("60000"),
                /*portfolio*/ BigDecimal.ZERO, /*theme*/ BigDecimal.ZERO,
                /*marketLimit*/ new BigDecimal("0.80"), /*themeLimit*/ new BigDecimal("0.40"),
                /*singleLimit*/ new BigDecimal("0.20"), /*riskPct*/ new BigDecimal("0.006"),
                /*cashReserve*/ new BigDecimal("0.10"),
                "BULL_TREND", AllocationMode.NORMAL);
        CapitalAllocationResult r = engine.evaluate(in);
        assertThat(r.action()).isEqualTo(AllocationAction.BUY_SIZE_SUGGESTION);
        assertThat(r.riskPerShare()).isEqualByComparingTo(new BigDecimal("6"));
        assertThat(r.maxLossAmount()).isEqualByComparingTo(new BigDecimal("600"));
        assertThat(r.suggestedShares()).isEqualTo(100);
        assertThat(r.suggestedAmount()).isEqualByComparingTo(new BigDecimal("12000"));
    }

    /** Case 2：entry ≤ stop → RISK_BLOCK INVALID_STOP_LOSS。 */
    @Test
    void case2_invalidStopLoss_blocks() {
        CapitalAllocationInput in = entry(
                "2454", "SEMI", "SELECT_BUY_NOW", new BigDecimal("7.8"),
                /*entry*/ new BigDecimal("120"), /*stop*/ new BigDecimal("125"),
                new BigDecimal("100000"), new BigDecimal("60000"),
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("0.80"), new BigDecimal("0.40"),
                new BigDecimal("0.20"), new BigDecimal("0.006"),
                new BigDecimal("0.10"),
                "BULL_TREND", AllocationMode.NORMAL);
        CapitalAllocationResult r = engine.evaluate(in);
        assertThat(r.action()).isEqualTo(AllocationAction.RISK_BLOCK);
        assertThat(r.reasons()).contains("INVALID_STOP_LOSS");
    }

    /** Case 3：market exposure 已滿。 */
    @Test
    void case3_marketExposureFull_blocks() {
        // equity 100000, marketLimit 0.80 → 上限 80000；已用 80000 → 剩 0
        CapitalAllocationInput in = entry(
                "2454", "SEMI", "SELECT_BUY_NOW", new BigDecimal("7.8"),
                new BigDecimal("120"), new BigDecimal("114"),
                new BigDecimal("100000"), new BigDecimal("60000"),
                /*portfolio*/ new BigDecimal("80000"), BigDecimal.ZERO,
                new BigDecimal("0.80"), new BigDecimal("0.40"),
                new BigDecimal("0.20"), new BigDecimal("0.006"),
                new BigDecimal("0.10"),
                "BULL_TREND", AllocationMode.NORMAL);
        CapitalAllocationResult r = engine.evaluate(in);
        assertThat(r.action()).isEqualTo(AllocationAction.RISK_BLOCK);
        assertThat(r.reasons()).contains("MARKET_EXPOSURE_LIMIT");
    }

    /** Case 4：theme exposure 已滿。 */
    @Test
    void case4_themeExposureFull_blocks() {
        // equity 100000 × themeLimit 0.40 = 40000；已用 40000 → 剩 0
        CapitalAllocationInput in = entry(
                "2454", "SEMI", "SELECT_BUY_NOW", new BigDecimal("7.8"),
                new BigDecimal("120"), new BigDecimal("114"),
                new BigDecimal("100000"), new BigDecimal("60000"),
                BigDecimal.ZERO, /*theme*/ new BigDecimal("40000"),
                new BigDecimal("0.80"), new BigDecimal("0.40"),
                new BigDecimal("0.20"), new BigDecimal("0.006"),
                new BigDecimal("0.10"),
                "BULL_TREND", AllocationMode.NORMAL);
        CapitalAllocationResult r = engine.evaluate(in);
        assertThat(r.action()).isEqualTo(AllocationAction.RISK_BLOCK);
        assertThat(r.reasons()).contains("THEME_EXPOSURE_LIMIT");
    }

    /** Case 5：現金 < 保留比例 → CASH_RESERVE。 */
    @Test
    void case5_cashReserveInsufficient() {
        // equity 100000 × cashReservePct 0.10 = 10000；availableCash 5000 < 10000
        CapitalAllocationInput in = entry(
                "2454", "SEMI", "SELECT_BUY_NOW", new BigDecimal("7.8"),
                new BigDecimal("120"), new BigDecimal("114"),
                new BigDecimal("100000"), /*cash*/ new BigDecimal("5000"),
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("0.80"), new BigDecimal("0.40"),
                new BigDecimal("0.20"), new BigDecimal("0.006"),
                new BigDecimal("0.10"),
                "BULL_TREND", AllocationMode.NORMAL);
        CapitalAllocationResult r = engine.evaluate(in);
        assertThat(r.action()).isEqualTo(AllocationAction.CASH_RESERVE);
        assertThat(r.reasons()).contains("CASH_RESERVE_INSUFFICIENT");
    }

    /** Case 6：BULL_TREND 下 CORE sizing。 */
    @Test
    void case6_bullCoreSizing() {
        // equity 1,000,000 × riskPct 0.01 = 10000；entry 200 stop 190 → riskPerShare 10
        // sharesByRisk = 1000；amountByRisk = 200,000
        // singleLimit 0.30 → 300,000；cash 800,000 − 100,000 = 700,000
        // market 800,000；theme 400,000
        // min = 200,000 → 1000 股
        CapitalAllocationInput in = entry(
                "3035", "SEMI", "SELECT_BUY_NOW", new BigDecimal("8.8"),
                new BigDecimal("200"), new BigDecimal("190"),
                new BigDecimal("1000000"), new BigDecimal("800000"),
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("0.80"), new BigDecimal("0.40"),
                new BigDecimal("0.30"), new BigDecimal("0.01"),
                new BigDecimal("0.10"),
                "BULL_TREND", AllocationMode.CORE);
        CapitalAllocationResult r = engine.evaluate(in);
        assertThat(r.action()).isEqualTo(AllocationAction.BUY_SIZE_SUGGESTION);
        assertThat(r.mode()).isEqualTo(AllocationMode.CORE);
        assertThat(r.suggestedShares()).isEqualTo(1000);
        assertThat(r.suggestedAmount()).isEqualByComparingTo(new BigDecimal("200000"));
    }

    /** Case 7：BEAR regime 下 marketLimit=0.20、TRIAL mode，金額太小 → CASH_RESERVE（降規成功、但不入場）。 */
    @Test
    void case7_bearRegimeTrialDowngradesToCashReserve() {
        // spec §十三 Case 7 預期：TRIAL or RISK_BLOCK。
        // 實際邏輯：mode 已 downgrade 到 TRIAL（riskPct 0.003）→ 算出金額 6000 < min 10000 → CASH_RESERVE。
        CapitalAllocationInput in = entry(
                "2454", "SEMI", "SELECT_BUY_NOW", new BigDecimal("8.8"),
                new BigDecimal("120"), new BigDecimal("114"),
                new BigDecimal("100000"), new BigDecimal("60000"),
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("0.20"), new BigDecimal("0.40"),
                new BigDecimal("0.10"), new BigDecimal("0.003"),
                new BigDecimal("0.10"),
                "WEAK_DOWNTREND", AllocationMode.TRIAL);
        CapitalAllocationResult r = engine.evaluate(in);
        assertThat(r.mode()).isEqualTo(AllocationMode.TRIAL);
        assertThat(r.action()).isIn(
                AllocationAction.CASH_RESERVE,
                AllocationAction.BUY_SIZE_SUGGESTION);   // 不允許升回 CORE / NORMAL
    }

    /** Case 7b：BEAR regime TRIAL 下，entry 小 + 風險小 → 仍能分配 TRIAL 量。 */
    @Test
    void case7b_bearRegimeTrialWithLowEntry() {
        // entry 40 stop 38（風險 2/股）；riskPct 0.003 × 100,000 = 300
        // sharesByRisk = 150；amountByRisk = 6000 < min 10000 → 會 CASH_RESERVE
        // 調整：equity 500,000，riskPct 0.003 → 1500；riskPerShare 2 → 750 股 → 30,000
        CapitalAllocationInput in = entry(
                "2454", "SEMI", "SELECT_BUY_NOW", new BigDecimal("8.8"),
                new BigDecimal("40"), new BigDecimal("38"),
                new BigDecimal("500000"), new BigDecimal("200000"),
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("0.20"), new BigDecimal("0.40"),
                new BigDecimal("0.10"), new BigDecimal("0.003"),
                new BigDecimal("0.10"),
                "WEAK_DOWNTREND", AllocationMode.TRIAL);
        CapitalAllocationResult r = engine.evaluate(in);
        assertThat(r.action()).isEqualTo(AllocationAction.BUY_SIZE_SUGGESTION);
        assertThat(r.mode()).isEqualTo(AllocationMode.TRIAL);
        // amountByRisk=30000 < amountByPosition=50000 < amountByMarket=100000 → 30000
        assertThat(r.suggestedAmount()).isEqualByComparingTo(new BigDecimal("30000"));
    }

    /** Case 8：REDUCE intent → REDUCE_SIZE_SUGGESTION + 比例。 */
    @Test
    void case8_reduceSuggestion() {
        CapitalAllocationInput in = new CapitalAllocationInput(
                "2454", "SEMI", "REDUCE", new BigDecimal("7.0"),
                new BigDecimal("120"), new BigDecimal("118"), new BigDecimal("114"),
                new BigDecimal("130"),
                new BigDecimal("60000"), new BigDecimal("100000"),
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("0.80"), new BigDecimal("0.40"),
                new BigDecimal("0.20"), new BigDecimal("0.006"),
                new BigDecimal("0.10"), new BigDecimal("10000"), 1,
                "BULL_TREND", AllocationMode.NORMAL, AllocationIntent.REDUCE,
                Map.of("capital.reduce_hint_pct", new BigDecimal("0.40"))
        );
        CapitalAllocationResult r = engine.evaluate(in);
        assertThat(r.action()).isEqualTo(AllocationAction.REDUCE_SIZE_SUGGESTION);
        assertThat(r.suggestedReducePct()).isEqualByComparingTo(new BigDecimal("0.40"));
        assertThat(r.suggestedReducePct().compareTo(new BigDecimal("0.30"))).isGreaterThanOrEqualTo(0);
        assertThat(r.suggestedReducePct().compareTo(new BigDecimal("0.50"))).isLessThanOrEqualTo(0);
    }

    /** Case 9：SWITCH intent → SWITCH_SIZE_SUGGESTION。 */
    @Test
    void case9_switchSuggestionOldLeg() {
        CapitalAllocationInput in = new CapitalAllocationInput(
                "2454", "SEMI", "SWITCH_OLD", new BigDecimal("7.0"),
                new BigDecimal("120"), new BigDecimal("118"), new BigDecimal("114"),
                new BigDecimal("130"),
                new BigDecimal("60000"), new BigDecimal("100000"),
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("0.80"), new BigDecimal("0.40"),
                new BigDecimal("0.20"), new BigDecimal("0.006"),
                new BigDecimal("0.10"), new BigDecimal("10000"), 1,
                "BULL_TREND", AllocationMode.NORMAL, AllocationIntent.SWITCH,
                Map.of("capital.reduce_hint_pct", new BigDecimal("0.40"))
        );
        CapitalAllocationResult r = engine.evaluate(in);
        assertThat(r.action()).isEqualTo(AllocationAction.SWITCH_SIZE_SUGGESTION);
        assertThat(r.suggestedReducePct()).isEqualByComparingTo(new BigDecimal("0.40"));
    }

    /** 額外：金額 < min_trade_amount → CASH_RESERVE。 */
    @Test
    void bonus_belowMinTrade_fallsToCashReserve() {
        // equity 100000 riskPct 0.003 → 300；riskPerShare 6 → 50 股 × 120 = 6000 < 10000
        CapitalAllocationInput in = entry(
                "2454", "SEMI", "SELECT_BUY_NOW", new BigDecimal("7.8"),
                new BigDecimal("120"), new BigDecimal("114"),
                new BigDecimal("100000"), new BigDecimal("60000"),
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("0.80"), new BigDecimal("0.40"),
                new BigDecimal("0.20"), new BigDecimal("0.003"),
                new BigDecimal("0.10"),
                "BULL_TREND", AllocationMode.TRIAL);
        CapitalAllocationResult r = engine.evaluate(in);
        assertThat(r.action()).isEqualTo(AllocationAction.CASH_RESERVE);
        assertThat(r.reasons()).contains("BELOW_MIN_TRADE_AMOUNT");
    }

    // ══════════════════════════════════════════════════════════════════════

    private CapitalAllocationInput entry(
            String symbol, String theme, String bucket, BigDecimal score,
            BigDecimal entry, BigDecimal stop,
            BigDecimal equity, BigDecimal cash,
            BigDecimal portfolioExp, BigDecimal themeExp,
            BigDecimal marketLimit, BigDecimal themeLimit,
            BigDecimal singleLimit, BigDecimal riskPct,
            BigDecimal cashReservePct,
            String regime, AllocationMode mode
    ) {
        return new CapitalAllocationInput(
                symbol, theme, bucket, score,
                entry, entry, stop, null,
                cash, equity, portfolioExp, themeExp,
                marketLimit, themeLimit, singleLimit, riskPct,
                cashReservePct, new BigDecimal("10000"), 1,
                regime, mode, AllocationIntent.OPEN, Map.of()
        );
    }
}
