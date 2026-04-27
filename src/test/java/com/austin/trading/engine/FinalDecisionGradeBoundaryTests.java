package com.austin.trading.engine;

import com.austin.trading.domain.enums.MarketSession;
import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import com.austin.trading.dto.request.FinalDecisionEvaluateRequest;
import com.austin.trading.dto.response.FinalDecisionResponse;
import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0.1 Grade Threshold Boundary tests.
 *
 * <p>NEW thresholds（P0.1 loosen，從 score_config DEFAULTS 對齊）：</p>
 * <ul>
 *   <li>scoring.grade_ap_min: 8.8 → 8.2</li>
 *   <li>scoring.grade_a_min:  8.2 → 7.5</li>
 *   <li>scoring.grade_b_min:  7.4 → 6.5</li>
 *   <li>scoring.rr_min_ap:    2.5 → 2.2</li>
 * </ul>
 *
 * <p>動機：過去 30 天 max final_rank_score = 7.00，舊 B 門檻 7.4 → 0 ENTER；
 * 放寬到 6.5 後，得以放出 B_TRIAL 試單。</p>
 *
 * <p>每個邊界測試使用 {@code mainStream=false}（所以 adjustedRank == finalRankScore，
 * 不會觸發 +0.3 boost），以便精準檢驗門檻。</p>
 */
class FinalDecisionGradeBoundaryTests {

    private FinalDecisionEngine engine;
    private ScoreConfigService config;
    private PriceGateEvaluator priceGateEvaluator;

    @BeforeEach
    void setUp() {
        config = mock(ScoreConfigService.class);
        // ── P0.1 NEW thresholds ─────────────────────────────────────────
        when(config.getDecimal(eq("scoring.grade_ap_min"), any())).thenReturn(new BigDecimal("8.2"));
        when(config.getDecimal(eq("scoring.grade_a_min"),  any())).thenReturn(new BigDecimal("7.5"));
        when(config.getDecimal(eq("scoring.grade_b_min"),  any())).thenReturn(new BigDecimal("6.5"));
        when(config.getDecimal(eq("scoring.rr_min_ap"),    any())).thenReturn(new BigDecimal("2.2"));
        when(config.getDecimal(eq("ranking.main_stream_boost"), any())).thenReturn(new BigDecimal("0.3"));
        // plan.* 不影響 INTRADAY_ENTRY，但 PriceGateEvaluator 仍會讀 priceGate 三個 config
        when(config.getDecimal(eq("plan.primary_min"),          any())).thenReturn(new BigDecimal("5.5"));
        when(config.getDecimal(eq("plan.backup_min"),           any())).thenReturn(new BigDecimal("4.0"));
        when(config.getDecimal(eq("plan.sector_indicator_min"), any())).thenReturn(new BigDecimal("7.8"));
        when(config.getDecimal(eq("plan.avoid_score_max"),      any())).thenReturn(new BigDecimal("3.5"));
        when(config.getInt(eq("decision.max_pick_aplus"), anyInt())).thenReturn(2);
        when(config.getInt(eq("decision.max_pick_a"),     anyInt())).thenReturn(2);
        when(config.getInt(eq("decision.max_pick_b"),     anyInt())).thenReturn(1);
        when(config.getInt(eq("plan.max_primary"),        anyInt())).thenReturn(2);
        when(config.getInt(eq("plan.max_backup"),         anyInt())).thenReturn(3);
        when(config.getBoolean(eq("decision.require_entry_trigger"), anyBoolean())).thenReturn(true);
        when(config.getString(any(), any())).thenReturn("A");
        // PriceGate 預設值（沿用 FinalDecisionEngineTests pattern）
        when(config.getDecimal(eq("trading.price_gate.low_volume_ratio_threshold"), any()))
                .thenReturn(new BigDecimal("0.8"));
        when(config.getDecimal(eq("trading.price_gate.far_from_open_pct_threshold"), any()))
                .thenReturn(new BigDecimal("0.01"));
        when(config.getDecimal(eq("trading.price_gate.bull_shallow_drop_pct_threshold"), any()))
                .thenReturn(new BigDecimal("0.01"));
        when(config.getBoolean(eq("trading.status.allow_trade"), anyBoolean())).thenReturn(true);
        priceGateEvaluator = new PriceGateEvaluator(config);
        engine = new FinalDecisionEngine(config, priceGateEvaluator);
    }

    // ─── A_PLUS 邊界（grade_ap_min = 8.2，rr_min_ap = 2.2）─────────────────────

    @Test
    void score_8_20_rr_2_2_assignsAPlusBucket() {
        FinalDecisionResponse r = engine.evaluate(
                evalReq(candidate("2330", 2.2, new BigDecimal("8.20"))),
                MarketSession.LIVE_TRADING);
        assertEquals("ENTER", r.decision());
        assertEquals(1, r.selectedStocks().size());
        assertTrue(r.summary().contains("A+ 等級標的已確認"),
                "8.20 + rr=2.2 應落入 A_PLUS bucket，summary 應為 A+ 主攻：" + r.summary());
    }

    @Test
    void score_8_19_assignsANormalBucket() {
        // 8.19 < 8.2 → 跌出 A+，但 ≥ 7.5 → A_NORMAL
        FinalDecisionResponse r = engine.evaluate(
                evalReq(candidate("2330", 2.2, new BigDecimal("8.19"))),
                MarketSession.LIVE_TRADING);
        assertEquals("ENTER", r.decision());
        assertEquals(1, r.selectedStocks().size());
        assertTrue(r.summary().contains("A 等級候選")
                        || r.summary().contains("正常倉位"),
                "8.19 應落入 A_NORMAL bucket（正常倉位 0.7x）：" + r.summary());
        assertTrue(!r.summary().contains("A+ 等級標的已確認"),
                "8.19 不該被歸為 A+ 主攻 bucket：" + r.summary());
    }

    // ─── A_NORMAL 邊界（grade_a_min = 7.5）────────────────────────────────────

    @Test
    void score_7_50_assignsANormalBucket() {
        FinalDecisionResponse r = engine.evaluate(
                evalReq(candidate("2330", 2.0, new BigDecimal("7.50"))),
                MarketSession.LIVE_TRADING);
        assertEquals("ENTER", r.decision());
        assertEquals(1, r.selectedStocks().size());
        assertTrue(r.summary().contains("A 等級候選")
                        || r.summary().contains("正常倉位"),
                "7.50 應落入 A_NORMAL bucket：" + r.summary());
        assertTrue(!r.summary().contains("A+ 等級標的已確認"),
                "7.50 不該被歸為 A+ 主攻 bucket：" + r.summary());
    }

    @Test
    void score_7_49_assignsBTrialBucket() {
        // 7.49 < 7.5 → 跌出 A，但 ≥ 6.5 → B_TRIAL
        FinalDecisionResponse r = engine.evaluate(
                evalReq(candidate("2330", 2.0, new BigDecimal("7.49"))),
                MarketSession.LIVE_TRADING);
        assertEquals("ENTER", r.decision());
        assertEquals(1, r.selectedStocks().size());
        assertTrue(r.summary().contains("B 等級候選")
                        || r.summary().contains("試單"),
                "7.49 應落入 B_TRIAL bucket（試單 0.5x）：" + r.summary());
    }

    // ─── B_TRIAL 邊界（grade_b_min = 6.5）─────────────────────────────────────

    @Test
    void score_6_50_assignsBTrialBucket() {
        FinalDecisionResponse r = engine.evaluate(
                evalReq(candidate("2330", 1.8, new BigDecimal("6.50"))),
                MarketSession.LIVE_TRADING);
        assertEquals("ENTER", r.decision());
        assertEquals(1, r.selectedStocks().size());
        assertTrue(r.summary().contains("B 等級候選")
                        || r.summary().contains("試單"),
                "6.50 應落入 B_TRIAL bucket：" + r.summary());
    }

    @Test
    void score_6_49_rejectsBelowBGrade() {
        // 6.49 < 6.5 → C 級，不入桶 → REST
        FinalDecisionResponse r = engine.evaluate(
                evalReq(candidate("2330", 1.8, new BigDecimal("6.49"))),
                MarketSession.LIVE_TRADING);
        assertEquals("REST", r.decision(),
                "6.49 應低於 B 門檻 (6.5) 而被拒絕，整體 decision = REST");
        assertEquals(0, r.selectedStocks().size());
        assertTrue(r.rejectedReasons().stream().anyMatch(s -> s.contains("等級 C")
                        || s.contains("BELOW_B_GRADE") || s.contains("rank")),
                "rejected reasons 應指出此檔被歸為 C / 低於 B 門檻：" + r.rejectedReasons());
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private FinalDecisionEvaluateRequest evalReq(FinalDecisionCandidateRequest c) {
        // marketGrade=A 確保不被市場層級 hard REST；hasPosition=false 不影響 LATE 邏輯（EARLY）
        return new FinalDecisionEvaluateRequest("A", "NONE", "EARLY", false, List.of(c));
    }

    /**
     * 邊界候選股 builder：mainStream=false 確保 adjustedRank == finalRankScore（無 +0.3 boost），
     * belowOpen=false / belowPrevClose=false 確保不被 priceGate 攔截，
     * entryTriggered=true 確保不被 ExecutionTimingEngine 影響。
     */
    private FinalDecisionCandidateRequest candidate(String code, double rr, BigDecimal finalRankScore) {
        return new FinalDecisionCandidateRequest(
                code,
                "TEST-" + code,
                "VALUE_FAIR",
                "BREAKOUT",
                rr,
                true,     // includeInFinalPlan
                false,    // mainStream（boundary 測試需精準門檻，禁用 boost）
                false,    // falseBreakout
                false,    // belowOpen
                false,    // belowPrevClose
                false,    // nearDayHigh
                true,     // stopLossReasonable
                "boundary-test",
                "100-102",
                98.0,
                108.0,
                115.0,
                null, null, null,        // javaStructureScore, claudeScore, codexScore
                finalRankScore,
                false,                   // isVetoed
                null, null,              // baseScore, hasTheme
                null, null,              // themeRank, finalThemeScore
                null, null,              // consensusScore, disagreementPenalty
                null, null, false,       // volumeSpike, priceNotBreakHigh, entryTooExtended
                true                     // entryTriggered
        );
    }
}
