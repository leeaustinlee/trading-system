package com.austin.trading.engine;

import com.austin.trading.domain.enums.DecisionPlanningMode;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FinalDecisionEngine tests — v2.6 MVP Refactor：A+ primary / A normal / B trial 三層。
 */
class FinalDecisionEngineTests {

    private FinalDecisionEngine engine;
    private ScoreConfigService config;
    private PriceGateEvaluator priceGateEvaluator;

    @BeforeEach
    void setUp() {
        config = mock(ScoreConfigService.class);
        when(config.getDecimal(eq("scoring.grade_ap_min"), any())).thenReturn(new BigDecimal("8.5"));
        when(config.getDecimal(eq("scoring.grade_a_min"),  any())).thenReturn(new BigDecimal("7.6"));
        when(config.getDecimal(eq("scoring.grade_b_min"),  any())).thenReturn(new BigDecimal("6.8"));
        when(config.getDecimal(eq("scoring.rr_min_ap"),    any())).thenReturn(new BigDecimal("2.2"));
        when(config.getDecimal(eq("ranking.main_stream_boost"), any())).thenReturn(new BigDecimal("0.3"));
        // v2.8 盤後規劃 config（P0.9：plan.* 專用門檻，比盤中 A/B 低 ~1.5）
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
        // v2.9 Gate 6/7: PriceGateEvaluator 會讀 3 個 config，測試用 default 值。
        when(config.getDecimal(eq("trading.price_gate.low_volume_ratio_threshold"), any()))
                .thenReturn(new BigDecimal("0.8"));
        when(config.getDecimal(eq("trading.price_gate.far_from_open_pct_threshold"), any()))
                .thenReturn(new BigDecimal("0.01"));
        when(config.getDecimal(eq("trading.price_gate.bull_shallow_drop_pct_threshold"), any()))
                .thenReturn(new BigDecimal("0.01"));
        priceGateEvaluator = new PriceGateEvaluator(config);
        engine = new FinalDecisionEngine(config, priceGateEvaluator);
    }

    // ── v2.7 Session gate ────────────────────────────────────────────────────

    @Test
    void sessionPremarket_returnsWait() {
        FinalDecisionResponse result = engine.evaluate(
                new FinalDecisionEvaluateRequest("A", "NONE", "EARLY", false, List.of(
                        candidate("2330", 2.5, "VALUE_FAIR", new BigDecimal("9.2"))
                )),
                MarketSession.PREMARKET);
        assertEquals("WAIT", result.decision());
        assertTrue(result.summary().contains("盤前"),
                "PREMARKET summary 應含『盤前』文字：" + result.summary());
        assertEquals(0, result.selectedStocks().size());
    }

    @Test
    void sessionOpenValidation_returnsWait() {
        FinalDecisionResponse result = engine.evaluate(
                new FinalDecisionEvaluateRequest("A", "NONE", "EARLY", false, List.of(
                        candidate("2330", 2.5, "VALUE_FAIR", new BigDecimal("9.2"))
                )),
                MarketSession.OPEN_VALIDATION);
        assertEquals("WAIT", result.decision());
        assertTrue(result.summary().contains("開盤驗證"),
                "OPEN_VALIDATION summary 應含『開盤驗證』：" + result.summary());
    }

    @Test
    void sessionLiveTrading_producesNormalDecision() {
        FinalDecisionResponse result = engine.evaluate(
                new FinalDecisionEvaluateRequest("A", "NONE", "EARLY", false, List.of(
                        candidate("2330", 2.5, "VALUE_FAIR", new BigDecimal("9.2"))
                )),
                MarketSession.LIVE_TRADING);
        assertEquals("ENTER", result.decision());
        assertEquals(1, result.selectedStocks().size());
    }

    // ── 市場層級 hard REST ───────────────────────────────────────────────────

    @Test
    void marketGradeC_rest() {
        FinalDecisionResponse result = engine.evaluate(new FinalDecisionEvaluateRequest(
                "C", "NONE", "EARLY", false, List.of()),
                MarketSession.LIVE_TRADING);
        assertEquals("REST", result.decision());
    }

    @Test
    void decisionLocked_rest() {
        FinalDecisionResponse result = engine.evaluate(new FinalDecisionEvaluateRequest(
                "A", "LOCKED", "EARLY", false,
                List.of(candidate("2330", 2.5, "VALUE_FAIR", new BigDecimal("9.2")))),
                MarketSession.LIVE_TRADING);
        assertEquals("REST", result.decision());
    }

    // ── A+ bucket（PRIMARY）──────────────────────────────────────────────────

    @Test
    void twoApCandidates_enterPrimary() {
        FinalDecisionResponse result = engine.evaluate(new FinalDecisionEvaluateRequest(
                "A", "NONE", "EARLY", false,
                List.of(
                        candidate("2330", 2.5, "VALUE_FAIR", new BigDecimal("9.2")),
                        candidate("2303", 2.6, "VALUE_LOW",  new BigDecimal("8.9"))
                )),
                MarketSession.LIVE_TRADING);
        assertEquals("ENTER", result.decision());
        assertEquals(2, result.selectedStocks().size());
        assertEquals("2330", result.selectedStocks().get(0).stockCode());
        assertTrue(result.summary().contains("A+"));
    }

    @Test
    void preferAPlusOverA() {
        // 1 檔 A+(9.0) + 1 檔 A(8.0) → 只選 A+（A bucket 被跳過）
        FinalDecisionResponse result = engine.evaluate(new FinalDecisionEvaluateRequest(
                "A", "NONE", "EARLY", false,
                List.of(
                        candidate("2330", 2.5, "VALUE_FAIR", new BigDecimal("9.0")),
                        candidate("2303", 2.5, "VALUE_LOW",  new BigDecimal("8.0"))
                )),
                MarketSession.LIVE_TRADING);
        assertEquals("ENTER", result.decision());
        assertEquals(1, result.selectedStocks().size());
        assertEquals("2330", result.selectedStocks().get(0).stockCode());
        assertTrue(result.summary().contains("A+"));
    }

    // ── A bucket（NORMAL）— Finding 1 核心測試 ───────────────────────────────

    @Test
    void noApButHasA_enterNormal() {
        // 原本系統這裡會 REST；v2.6 MVP 改成以 A 為正常倉進場
        FinalDecisionResponse result = engine.evaluate(new FinalDecisionEvaluateRequest(
                "B", "NONE", "EARLY", false,
                List.of(
                        candidate("2330", 2.0, "VALUE_FAIR", new BigDecimal("8.2")),
                        candidate("2303", 2.1, "VALUE_LOW",  new BigDecimal("7.8"))
                )),
                MarketSession.LIVE_TRADING);
        assertEquals("ENTER", result.decision(),
                "B 市場 + A 候選時不應 REST（v2.6 三層分級）");
        assertEquals(2, result.selectedStocks().size());
        assertEquals("2330", result.selectedStocks().get(0).stockCode());
        assertTrue(result.summary().contains("正常倉位")
                || result.summary().contains("NORMAL")
                || result.summary().contains("A "),
                "summary 應指示 A 等級進場：" + result.summary());
    }

    @Test
    void aCandidates_respectMaxPickA() {
        when(config.getInt(eq("decision.max_pick_a"), anyInt())).thenReturn(1);
        FinalDecisionResponse result = engine.evaluate(new FinalDecisionEvaluateRequest(
                "A", "NONE", "EARLY", false,
                List.of(
                        candidate("2330", 2.0, "VALUE_FAIR", new BigDecimal("8.1")),
                        candidate("2303", 2.0, "VALUE_LOW",  new BigDecimal("7.8"))
                )),
                MarketSession.LIVE_TRADING);
        assertEquals("ENTER", result.decision());
        assertEquals(1, result.selectedStocks().size());
    }

    // ── B bucket（TRIAL）──────────────────────────────────────────────────────

    @Test
    void onlyB_enterTrial() {
        // 沒 A+/A，只有 B → 試單 1 檔
        FinalDecisionResponse result = engine.evaluate(new FinalDecisionEvaluateRequest(
                "B", "NONE", "EARLY", false,
                List.of(
                        candidate("2330", 1.8, "VALUE_FAIR", new BigDecimal("7.2")),
                        candidate("2303", 1.8, "VALUE_LOW",  new BigDecimal("6.9"))
                )),
                MarketSession.LIVE_TRADING);
        assertEquals("ENTER", result.decision());
        assertEquals(1, result.selectedStocks().size());
        assertEquals("2330", result.selectedStocks().get(0).stockCode());
        assertTrue(result.summary().contains("試單") || result.summary().contains("TRIAL")
                || result.summary().contains("B "),
                "summary 應指示 B trial：" + result.summary());
    }

    @Test
    void allBelowB_rest() {
        // 所有 score（含 mainStream boost +0.3）< grade_b_min(6.8) → REST
        // 2330: 6.4 + 0.3 = 6.7 < 6.8 → C
        // 2303: 5.9 + 0.3 = 6.2 < 6.8 → C
        FinalDecisionResponse result = engine.evaluate(new FinalDecisionEvaluateRequest(
                "A", "NONE", "EARLY", false,
                List.of(
                        candidate("2330", 2.0, "VALUE_FAIR", new BigDecimal("6.4")),
                        candidate("2303", 1.8, "VALUE_LOW",  new BigDecimal("5.9"))
                )),
                MarketSession.LIVE_TRADING);
        assertEquals("REST", result.decision());
    }

    // ── v2.7 A5: entryTriggered 不再由 FinalDecisionEngine 檢查 ─────────────

    @Test
    void entryTriggeredFalse_notBlockedByFinalDecision() {
        // v2.7：entryTriggered=false 不再被 FinalDecisionEngine 擋（交給 ExecutionTimingEngine）
        FinalDecisionResponse result = engine.evaluate(new FinalDecisionEvaluateRequest(
                "A", "NONE", "EARLY", false,
                List.of(candidateCustom("2330", 2.5, "VALUE_FAIR", new BigDecimal("9.2"),
                        /*mainStream*/ true, /*entryTriggered*/ false))),
                MarketSession.LIVE_TRADING);
        assertEquals("ENTER", result.decision(),
                "v2.7: entryTriggered=false 應由 ExecutionTimingEngine 擋，非 FinalDecisionEngine");
    }

    // ── v2.7 A6: mainStream 改 ranking boost ─────────────────────────────

    @Test
    void nonMainStream_stillEligible_withoutBoost() {
        // 2330 非主流 + score 8.0 → 無 boost → 仍入 A bucket（8.0 >= 7.6）
        FinalDecisionResponse result = engine.evaluate(new FinalDecisionEvaluateRequest(
                "A", "NONE", "EARLY", false,
                List.of(candidateCustom("2330", 2.0, "VALUE_FAIR", new BigDecimal("8.0"),
                        /*mainStream*/ false, /*entryTriggered*/ true))),
                MarketSession.LIVE_TRADING);
        assertEquals("ENTER", result.decision(),
                "v2.7: 非主流不應 hard block，應改 boost 機制");
    }

    @Test
    void mainStreamBoost_pushesBCandidateToA() {
        // 2330 主流 + score 7.5（原 < 7.6 應在 B）+ boost 0.3 → 7.8 → A bucket
        FinalDecisionResponse result = engine.evaluate(new FinalDecisionEvaluateRequest(
                "A", "NONE", "EARLY", false,
                List.of(candidateCustom("2330", 2.0, "VALUE_FAIR", new BigDecimal("7.5"),
                        /*mainStream*/ true, /*entryTriggered*/ true))),
                MarketSession.LIVE_TRADING);
        assertEquals("ENTER", result.decision());
        assertTrue(result.summary().contains("A")
                || result.summary().contains("正常"),
                "mainStream boost 應讓此候選進 A bucket：" + result.summary());
    }

    // ── v2.9 Gate 6/7: PriceGateEvaluator（belowOpen / belowPrevClose 條件式）────

    /**
     * v2.9：LIVE_TRADING + belowOpen=true + 無 VWAP / regime 資訊 → 降級 WAIT，不再直接 REST。
     * 取代舊 v2.7 `belowOpen_blockedOnlyInLiveTrading` 的一律 hard block 語意。
     */
    @Test
    void belowOpen_withoutVwapOrRegime_degradesToWait() {
        FinalDecisionResponse live = engine.evaluate(new FinalDecisionEvaluateRequest(
                "A", "NONE", "EARLY", false,
                List.of(candidateCustom("2330", 2.5, "VALUE_FAIR", new BigDecimal("9.2"),
                        true, true, /*belowOpen*/ true))),
                MarketSession.LIVE_TRADING);
        assertEquals("WAIT", live.decision(),
                "v2.9：belowOpen 無 VWAP 無 regime 資訊應降級 WAIT 等確認，不該直接 REST");
        assertTrue(live.summary() == null || !live.summary().contains("A+"),
                "不應帶 A+ 進場字樣");
    }

    /**
     * v2.9 Case 1：belowOpen + currentPrice 站回 VWAP + volumeRatio 正常 → WAIT（不可 REST）。
     */
    @Test
    void priceGate_case1_belowOpenButAboveVwap_waits() {
        FinalDecisionCandidateRequest c = priceGateCandidate(
                "2454", new BigDecimal("9.2"),
                /*belowOpen*/ true, /*belowPrevClose*/ false,
                /*current*/ new BigDecimal("2190"),
                /*open*/    new BigDecimal("2200"),
                /*prev*/    new BigDecimal("2180"),
                /*vwap*/    new BigDecimal("2185"),      // current 2190 > vwap 2185
                /*volRatio*/ new BigDecimal("1.10"),
                /*regime*/  "BULL_TREND");
        FinalDecisionResponse result = engine.evaluate(
                new FinalDecisionEvaluateRequest("A", "NONE", "EARLY", false, List.of(c)),
                MarketSession.LIVE_TRADING);
        assertEquals("WAIT", result.decision(),
                "站回 VWAP + 量能正常 → WAIT，不該 REST");
    }

    /**
     * v2.9 Case 2：belowOpen + belowVwap + lowVolume + farFromOpen 四條件齊全 → REST（hard block）。
     */
    @Test
    void priceGate_case2_belowOpenFullWeak_blocks() {
        FinalDecisionCandidateRequest c = priceGateCandidate(
                "2454", new BigDecimal("9.2"),
                true, false,
                /*current*/ new BigDecimal("2160"),
                /*open*/    new BigDecimal("2200"),
                /*prev*/    new BigDecimal("2180"),
                /*vwap*/    new BigDecimal("2180"),      // current 2160 < vwap 2180
                /*volRatio*/ new BigDecimal("0.60"),      // < 0.8
                /*regime*/  "BULL_TREND");
        // distance = (2160-2200)/2200 = -0.0182 (1.82% > 1%)
        FinalDecisionResponse result = engine.evaluate(
                new FinalDecisionEvaluateRequest("A", "NONE", "EARLY", false, List.of(c)),
                MarketSession.LIVE_TRADING);
        assertEquals("REST", result.decision(),
                "belowOpen + belowVwap + lowVolume + farFromOpen 四條件齊全應 hard block");
    }

    /**
     * v2.9 Case 3：belowPrevClose + BULL_TREND + 小跌 (0.5%) → WAIT。
     */
    @Test
    void priceGate_case3_belowPrevCloseBullShallow_waits() {
        FinalDecisionCandidateRequest c = priceGateCandidate(
                "2454", new BigDecimal("9.2"),
                false, true,
                new BigDecimal("2169.1"),           // drop 0.5%
                new BigDecimal("2200"),
                new BigDecimal("2180"),
                null, null,
                "BULL_TREND");
        FinalDecisionResponse result = engine.evaluate(
                new FinalDecisionEvaluateRequest("A", "NONE", "EARLY", false, List.of(c)),
                MarketSession.LIVE_TRADING);
        assertEquals("WAIT", result.decision(),
                "BULL_TREND 下跌破昨收 < 1% 應 WAIT 不 REST");
    }

    /**
     * v2.9 Case 4：belowPrevClose + RANGE_CHOP + 小跌 → REST。
     */
    @Test
    void priceGate_case4_belowPrevCloseRangeChop_blocks() {
        FinalDecisionCandidateRequest c = priceGateCandidate(
                "2454", new BigDecimal("9.2"),
                false, true,
                new BigDecimal("2169.1"),
                new BigDecimal("2200"),
                new BigDecimal("2180"),
                null, null,
                "RANGE_CHOP");
        FinalDecisionResponse result = engine.evaluate(
                new FinalDecisionEvaluateRequest("A", "NONE", "EARLY", false, List.of(c)),
                MarketSession.LIVE_TRADING);
        assertEquals("REST", result.decision(),
                "非 BULL_TREND 跌破昨收即 hard block");
    }

    /**
     * v2.9 Case 5：belowPrevClose + BULL_TREND + 深跌 (>=1%) → REST。
     */
    @Test
    void priceGate_case5_belowPrevCloseBullDeep_blocks() {
        FinalDecisionCandidateRequest c = priceGateCandidate(
                "2454", new BigDecimal("9.2"),
                false, true,
                new BigDecimal("2154"),           // drop 1.19% ≥ 1%
                new BigDecimal("2200"),
                new BigDecimal("2180"),
                null, null,
                "BULL_TREND");
        FinalDecisionResponse result = engine.evaluate(
                new FinalDecisionEvaluateRequest("A", "NONE", "EARLY", false, List.of(c)),
                MarketSession.LIVE_TRADING);
        assertEquals("REST", result.decision(),
                "BULL_TREND 下跌破昨收 ≥ 1% 仍應 hard block");
    }

    /**
     * v2.9.1 Case 3 強化：belowOpen + belowVwap + 高量（ratio > 1.0）+ farFromOpen → WAIT。
     * 量能未縮，代表有買盤承接，不是真弱勢；只要四條件中任一不成立就不該 BLOCK。
     */
    @Test
    void priceGate_v291_case3_belowOpenHighVolume_waits() {
        FinalDecisionCandidateRequest c = priceGateCandidate(
                "2454", new BigDecimal("9.2"),
                /*belowOpen*/ true, /*belowPrevClose*/ false,
                /*current*/ new BigDecimal("2160"),
                /*open*/    new BigDecimal("2200"),
                /*prev*/    new BigDecimal("2180"),
                /*vwap*/    new BigDecimal("2180"),    // below VWAP
                /*volRatio*/ new BigDecimal("1.25"),    // 高量，> 0.8 threshold
                /*regime*/  "BULL_TREND");
        FinalDecisionResponse result = engine.evaluate(
                new FinalDecisionEvaluateRequest("A", "NONE", "EARLY", false, List.of(c)),
                MarketSession.LIVE_TRADING);
        assertEquals("WAIT", result.decision(),
                "belowOpen + belowVwap + 高量 + farFromOpen → 高量破除 BLOCK 條件，應 WAIT");
    }

    /**
     * v2.9 Case 6：belowOpen + 無 VWAP + SELECT_BUY_NOW 精神（mainStream=true）→ WAIT。
     * 本測試只驗 engine 層，不走 Codex overlay；但此案例覆蓋「無 VWAP fallback」。
     */
    @Test
    void priceGate_case6_belowOpenNoVwap_waits() {
        FinalDecisionCandidateRequest c = priceGateCandidate(
                "2454", new BigDecimal("9.2"),
                true, false,
                new BigDecimal("2190"),
                new BigDecimal("2200"),
                new BigDecimal("2180"),
                null, null,                       // VWAP null
                "BULL_TREND");
        FinalDecisionResponse result = engine.evaluate(
                new FinalDecisionEvaluateRequest("A", "NONE", "EARLY", false, List.of(c)),
                MarketSession.LIVE_TRADING);
        assertEquals("WAIT", result.decision(),
                "無 VWAP fallback 應 WAIT，不可直接 REST");
    }

    // ── v2.8 POSTCLOSE_TOMORROW_PLAN 模式 ───────────────────────────────────

    @Test
    void postclosePlan_mixedCandidates_bucketsCorrectly() {
        // P0.9 盤後門檻：primary>=5.5, backup>=4.0, avoid<=3.5
        // 注意 mainStream=true 會加 +0.3 boost
        FinalDecisionResponse result = engine.evaluate(
                new FinalDecisionEvaluateRequest("B", "NONE", "EARLY", false, List.of(
                        candidate("8150", 2.0, "VALUE_FAIR", new BigDecimal("5.8")),   // +0.3 → 6.1 → primary
                        candidate("6770", 2.0, "VALUE_FAIR", new BigDecimal("4.2")),   // +0.3 → 4.5 → backup
                        candidate("2337", 2.0, "VALUE_FAIR", new BigDecimal("2.5"))    // +0.3 → 2.8 → avoid
                )),
                MarketSession.LIVE_TRADING,
                DecisionPlanningMode.POSTCLOSE_TOMORROW_PLAN);

        assertEquals("PLAN", result.decision());
        assertTrue(result.summary().contains("8150") || result.summary().contains("首選"),
                "summary 應含首選：" + result.summary());
        assertNotNull(result.planningPayload());
        Object primary = result.planningPayload().get("primaryCandidates");
        assertTrue(primary instanceof List && ((List<?>) primary).contains("8150"));
        Object backup = result.planningPayload().get("backupCandidates");
        assertTrue(backup instanceof List && ((List<?>) backup).contains("6770"));
        Object avoid = result.planningPayload().get("avoidSymbols");
        assertTrue(avoid instanceof List);
        assertTrue(((List<?>) avoid).stream().anyMatch(s -> s.toString().contains("2337")));
    }

    @Test
    void postclosePlan_ignoresDecisionLock() {
        // LOCKED 在盤後模式下應被忽略（日內 gate，不污染明日規劃）
        FinalDecisionResponse result = engine.evaluate(
                new FinalDecisionEvaluateRequest("A", "LOCKED", "EARLY", false, List.of(
                        candidate("8150", 2.0, "VALUE_FAIR", new BigDecimal("6.0"))
                )),
                MarketSession.LIVE_TRADING,
                DecisionPlanningMode.POSTCLOSE_TOMORROW_PLAN);
        assertEquals("PLAN", result.decision(),
                "POSTCLOSE_TOMORROW_PLAN 模式應忽略 decisionLock");
    }

    @Test
    void postclosePlan_notAffectedBySession() {
        // 盤後一定不在 LIVE_TRADING，但 plan 模式不用 session gate
        FinalDecisionResponse result = engine.evaluate(
                new FinalDecisionEvaluateRequest("A", "NONE", "EARLY", false, List.of(
                        candidate("8150", 2.0, "VALUE_FAIR", new BigDecimal("6.0"))
                )),
                MarketSession.PREMARKET,  // 盤前試撮時段
                DecisionPlanningMode.POSTCLOSE_TOMORROW_PLAN);
        assertEquals("PLAN", result.decision(),
                "POSTCLOSE_TOMORROW_PLAN 不受 session gate 限制");
    }

    @Test
    void postclosePlan_extendedHighScore_becomesSectorIndicator() {
        // 高分 + extended（如鎖漲停）→ 歸為族群燈號不追價
        FinalDecisionResponse result = engine.evaluate(
                new FinalDecisionEvaluateRequest("A", "NONE", "EARLY", false, List.of(
                        candidateCustom("2454", 2.5, "VALUE_FAIR",
                                new BigDecimal("8.2"),  // >= sector_indicator_min 7.8
                                /*mainStream*/ true,
                                /*entryTriggered*/ true,
                                /*belowOpen*/ false,
                                /*entryTooExtended*/ true)
                )),
                MarketSession.LIVE_TRADING,
                DecisionPlanningMode.POSTCLOSE_TOMORROW_PLAN);
        Object sectorInd = result.planningPayload().get("sectorIndicators");
        assertTrue(sectorInd instanceof List && ((List<?>) sectorInd).contains("2454"),
                "extended 高分股應進 sectorIndicators：" + sectorInd);
    }

    @Test
    void postclosePlan_emptyCandidates_fallbackSummary() {
        FinalDecisionResponse result = engine.evaluate(
                new FinalDecisionEvaluateRequest("A", "NONE", "EARLY", false, List.of()),
                MarketSession.LIVE_TRADING,
                DecisionPlanningMode.POSTCLOSE_TOMORROW_PLAN);
        assertEquals("PLAN", result.decision());
        assertTrue(result.summary().contains("無主規劃") || result.summary().contains("持倉管理"),
                "無候選時應輸出『持倉管理為主』類 summary：" + result.summary());
    }

    // ── Hard veto skip ───────────────────────────────────────────────────────

    @Test
    void vetoedCandidates_excluded() {
        // 有 A+ 但 isVetoed=true → 被跳過，fallback 到 A
        FinalDecisionResponse result = engine.evaluate(new FinalDecisionEvaluateRequest(
                "A", "NONE", "EARLY", false,
                List.of(
                        candidateWithVeto("2330", 2.5, "VALUE_FAIR", new BigDecimal("9.2"), true),
                        candidate("2303", 2.0, "VALUE_LOW",  new BigDecimal("8.0"))
                )),
                MarketSession.LIVE_TRADING);
        assertEquals("ENTER", result.decision());
        assertEquals("2303", result.selectedStocks().get(0).stockCode());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private FinalDecisionCandidateRequest candidate(String code, double rr,
                                                     String valuationMode, BigDecimal finalRankScore) {
        return candidateWithVeto(code, rr, valuationMode, finalRankScore, false);
    }

    private FinalDecisionCandidateRequest candidateWithVeto(String code, double rr,
                                                              String valuationMode, BigDecimal finalRankScore,
                                                              boolean vetoed) {
        return buildCandidate(code, rr, valuationMode, finalRankScore, vetoed,
                /*mainStream*/ true, /*entryTriggered*/ true, /*belowOpen*/ false, /*entryTooExtended*/ false);
    }

    private FinalDecisionCandidateRequest candidateCustom(String code, double rr,
                                                           String valuationMode, BigDecimal finalRankScore,
                                                           boolean mainStream, boolean entryTriggered) {
        return buildCandidate(code, rr, valuationMode, finalRankScore, false,
                mainStream, entryTriggered, false, false);
    }

    private FinalDecisionCandidateRequest candidateCustom(String code, double rr,
                                                           String valuationMode, BigDecimal finalRankScore,
                                                           boolean mainStream, boolean entryTriggered,
                                                           boolean belowOpen) {
        return buildCandidate(code, rr, valuationMode, finalRankScore, false,
                mainStream, entryTriggered, belowOpen, false);
    }

    private FinalDecisionCandidateRequest candidateCustom(String code, double rr,
                                                           String valuationMode, BigDecimal finalRankScore,
                                                           boolean mainStream, boolean entryTriggered,
                                                           boolean belowOpen, boolean entryTooExtended) {
        return buildCandidate(code, rr, valuationMode, finalRankScore, false,
                mainStream, entryTriggered, belowOpen, entryTooExtended);
    }

    private FinalDecisionCandidateRequest buildCandidate(String code, double rr,
                                                          String valuationMode, BigDecimal finalRankScore,
                                                          boolean vetoed, boolean mainStream,
                                                          boolean entryTriggered, boolean belowOpen,
                                                          boolean entryTooExtended) {
        return new FinalDecisionCandidateRequest(
                code,
                "TEST-" + code,
                valuationMode,
                "BREAKOUT",
                rr,
                true,
                mainStream,
                false,        // falseBreakout
                belowOpen,    // belowOpen
                false,        // belowPrevClose
                false,
                true,
                "ok",
                "100-102",
                98.0,
                108.0,
                115.0,
                null, null, null,   // javaStructureScore, claudeScore, codexScore
                finalRankScore, vetoed,
                null, null,         // baseScore, hasTheme
                null, null,         // themeRank, finalThemeScore
                null, null,         // consensusScore, disagreementPenalty
                null, null, entryTooExtended,   // volumeSpike, priceNotBreakHigh, entryTooExtended
                entryTriggered
        );
    }

    /**
     * v2.9 Gate 6/7 測試專用 builder：直接指定 priceGate 相關欄位。
     * distanceFromOpenPct / dropFromPrevClosePct 會自動從 currentPrice / open / prev 推導。
     */
    private FinalDecisionCandidateRequest priceGateCandidate(
            String code, BigDecimal finalRankScore,
            boolean belowOpen, boolean belowPrevClose,
            BigDecimal currentPrice, BigDecimal openPrice, BigDecimal previousClose,
            BigDecimal vwapPrice, BigDecimal volumeRatio, String regime) {
        BigDecimal distanceFromOpenPct = (currentPrice != null && openPrice != null && openPrice.signum() > 0)
                ? currentPrice.subtract(openPrice).divide(openPrice, 6, java.math.RoundingMode.HALF_UP)
                : null;
        BigDecimal dropFromPrevClosePct = (currentPrice != null && previousClose != null && previousClose.signum() > 0)
                ? previousClose.subtract(currentPrice).divide(previousClose, 6, java.math.RoundingMode.HALF_UP)
                : null;
        return new FinalDecisionCandidateRequest(
                code, "TEST-" + code, "VALUE_FAIR", "BREAKOUT",
                2.5, /*includeInFinalPlan*/ true, /*mainStream*/ true,
                /*falseBreakout*/ false, belowOpen, belowPrevClose,
                /*nearDayHigh*/ false, /*stopLossReasonable*/ true,
                "pricegate-test", "100-102",
                98.0, 108.0, 115.0,
                null, null, null,
                finalRankScore, false,
                null, null, null, null, null, null,
                null, null, false, /*entryTriggered*/ true,
                currentPrice, openPrice, previousClose,
                vwapPrice, volumeRatio,
                distanceFromOpenPct, dropFromPrevClosePct, regime
        );
    }
}
