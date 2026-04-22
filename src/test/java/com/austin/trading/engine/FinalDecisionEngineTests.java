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
 * FinalDecisionEngine tests — v2.6 MVP Refactor：A+ primary / A normal / B trial 三層。
 */
class FinalDecisionEngineTests {

    private FinalDecisionEngine engine;
    private ScoreConfigService config;

    @BeforeEach
    void setUp() {
        config = mock(ScoreConfigService.class);
        when(config.getDecimal(eq("scoring.grade_ap_min"), any())).thenReturn(new BigDecimal("8.5"));
        when(config.getDecimal(eq("scoring.grade_a_min"),  any())).thenReturn(new BigDecimal("7.6"));
        when(config.getDecimal(eq("scoring.grade_b_min"),  any())).thenReturn(new BigDecimal("6.8"));
        when(config.getDecimal(eq("scoring.rr_min_ap"),    any())).thenReturn(new BigDecimal("2.2"));
        when(config.getDecimal(eq("ranking.main_stream_boost"), any())).thenReturn(new BigDecimal("0.3"));
        when(config.getInt(eq("decision.max_pick_aplus"), anyInt())).thenReturn(2);
        when(config.getInt(eq("decision.max_pick_a"),     anyInt())).thenReturn(2);
        when(config.getInt(eq("decision.max_pick_b"),     anyInt())).thenReturn(1);
        when(config.getBoolean(eq("decision.require_entry_trigger"), anyBoolean())).thenReturn(true);
        when(config.getString(any(), any())).thenReturn("A");
        engine = new FinalDecisionEngine(config);
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

    // ── v2.7 A7: belowOpen / belowPrevClose session-gated ─────────────────

    @Test
    void belowOpen_blockedOnlyInLiveTrading() {
        // session=LIVE_TRADING + belowOpen=true → 擋
        FinalDecisionResponse live = engine.evaluate(new FinalDecisionEvaluateRequest(
                "A", "NONE", "EARLY", false,
                List.of(candidateCustom("2330", 2.5, "VALUE_FAIR", new BigDecimal("9.2"),
                        true, true, /*belowOpen*/ true))),
                MarketSession.LIVE_TRADING);
        assertEquals("REST", live.decision(),
                "LIVE_TRADING 時 belowOpen=true 應被 validateBasicConditions 擋");
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
                /*mainStream*/ true, /*entryTriggered*/ true, /*belowOpen*/ false);
    }

    private FinalDecisionCandidateRequest candidateCustom(String code, double rr,
                                                           String valuationMode, BigDecimal finalRankScore,
                                                           boolean mainStream, boolean entryTriggered) {
        return buildCandidate(code, rr, valuationMode, finalRankScore, false,
                mainStream, entryTriggered, false);
    }

    private FinalDecisionCandidateRequest candidateCustom(String code, double rr,
                                                           String valuationMode, BigDecimal finalRankScore,
                                                           boolean mainStream, boolean entryTriggered,
                                                           boolean belowOpen) {
        return buildCandidate(code, rr, valuationMode, finalRankScore, false,
                mainStream, entryTriggered, belowOpen);
    }

    private FinalDecisionCandidateRequest buildCandidate(String code, double rr,
                                                          String valuationMode, BigDecimal finalRankScore,
                                                          boolean vetoed, boolean mainStream,
                                                          boolean entryTriggered, boolean belowOpen) {
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
                null, null, null,   // volumeSpike, priceNotBreakHigh, entryTooExtended
                entryTriggered
        );
    }
}
