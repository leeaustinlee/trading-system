package com.austin.trading.engine;

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
        when(config.getInt(eq("decision.max_pick_aplus"), anyInt())).thenReturn(2);
        when(config.getInt(eq("decision.max_pick_a"),     anyInt())).thenReturn(2);
        when(config.getInt(eq("decision.max_pick_b"),     anyInt())).thenReturn(1);
        when(config.getBoolean(eq("decision.require_entry_trigger"), anyBoolean())).thenReturn(true);
        when(config.getString(any(), any())).thenReturn("A");
        engine = new FinalDecisionEngine(config);
    }

    // ── 市場層級 hard REST ───────────────────────────────────────────────────

    @Test
    void marketGradeC_rest() {
        FinalDecisionResponse result = engine.evaluate(new FinalDecisionEvaluateRequest(
                "C", "NONE", "EARLY", false, List.of()));
        assertEquals("REST", result.decision());
    }

    @Test
    void decisionLocked_rest() {
        FinalDecisionResponse result = engine.evaluate(new FinalDecisionEvaluateRequest(
                "A", "LOCKED", "EARLY", false,
                List.of(candidate("2330", 2.5, "VALUE_FAIR", new BigDecimal("9.2")))));
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
                )));
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
                )));
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
                )));
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
                )));
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
                )));
        assertEquals("ENTER", result.decision());
        assertEquals(1, result.selectedStocks().size());
        assertEquals("2330", result.selectedStocks().get(0).stockCode());
        assertTrue(result.summary().contains("試單") || result.summary().contains("TRIAL")
                || result.summary().contains("B "),
                "summary 應指示 B trial：" + result.summary());
    }

    @Test
    void allBelowB_rest() {
        // 所有 score < grade_b_min(6.8) → REST
        FinalDecisionResponse result = engine.evaluate(new FinalDecisionEvaluateRequest(
                "A", "NONE", "EARLY", false,
                List.of(
                        candidate("2330", 2.0, "VALUE_FAIR", new BigDecimal("6.5")),
                        candidate("2303", 1.8, "VALUE_LOW",  new BigDecimal("5.9"))
                )));
        assertEquals("REST", result.decision());
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
                )));
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
        return new FinalDecisionCandidateRequest(
                code,
                "TEST-" + code,
                valuationMode,
                "BREAKOUT",
                rr,
                true,
                true,
                false,
                false,
                false,
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
                true                // entryTriggered
        );
    }
}
