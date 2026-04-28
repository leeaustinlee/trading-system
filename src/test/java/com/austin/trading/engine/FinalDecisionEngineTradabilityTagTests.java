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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Batch Mom-C：FinalDecisionEngine 對 PowerShell screener 寫入 candidate_stock.payload_json.tradabilityTag
 * 的處理測試。
 *
 * <p>規則：</p>
 * <ul>
 *   <li>tag 含「不列主進場」→ hard block（從 bucket 排除，加入 rejected）</li>
 *   <li>tag 含「漲幅過大」或「僅參考」→ -1.0 finalRankScore 軟懲罰</li>
 *   <li>tag 為 null → 不影響</li>
 *   <li>feature flag final_decision.respect_tradability_tag.enabled=false → 完全略過 tag</li>
 * </ul>
 */
class FinalDecisionEngineTradabilityTagTests {

    private FinalDecisionEngine engine;
    private ScoreConfigService config;

    @BeforeEach
    void setUp() {
        config = mock(ScoreConfigService.class);
        // 既有 grade thresholds（與 FinalDecisionEngineTests 一致以保穩定）
        when(config.getDecimal(eq("scoring.grade_ap_min"), any())).thenReturn(new BigDecimal("8.5"));
        when(config.getDecimal(eq("scoring.grade_a_min"),  any())).thenReturn(new BigDecimal("7.5"));
        when(config.getDecimal(eq("scoring.grade_b_min"),  any())).thenReturn(new BigDecimal("6.5"));
        when(config.getDecimal(eq("scoring.rr_min_ap"),    any())).thenReturn(new BigDecimal("2.2"));
        when(config.getDecimal(eq("ranking.main_stream_boost"), any())).thenReturn(new BigDecimal("0.3"));
        when(config.getInt(eq("decision.max_pick_aplus"), anyInt())).thenReturn(2);
        when(config.getInt(eq("decision.max_pick_a"),     anyInt())).thenReturn(2);
        when(config.getInt(eq("decision.max_pick_b"),     anyInt())).thenReturn(1);
        when(config.getBoolean(eq("decision.require_entry_trigger"), anyBoolean())).thenReturn(true);
        when(config.getString(any(), any())).thenReturn("A");
        // priceGate 配置
        when(config.getDecimal(eq("trading.price_gate.low_volume_ratio_threshold"), any()))
                .thenReturn(new BigDecimal("0.8"));
        when(config.getDecimal(eq("trading.price_gate.far_from_open_pct_threshold"), any()))
                .thenReturn(new BigDecimal("0.01"));
        when(config.getDecimal(eq("trading.price_gate.bull_shallow_drop_pct_threshold"), any()))
                .thenReturn(new BigDecimal("0.01"));
        // kill switch
        when(config.getBoolean(eq("trading.status.allow_trade"), anyBoolean())).thenReturn(true);
        // tradabilityTag flag：預設 enabled
        when(config.getBoolean(eq("final_decision.respect_tradability_tag.enabled"), anyBoolean()))
                .thenReturn(true);
        when(config.getDecimal(eq("final_decision.tradability_tag.soft_penalty"), any()))
                .thenReturn(new BigDecimal("1.0"));

        PriceGateEvaluator priceGateEvaluator = new PriceGateEvaluator(config);
        engine = new FinalDecisionEngine(config, priceGateEvaluator);
    }

    @Test
    void tagBlock_candidateNotEntered_reasonInRejected() {
        // tradabilityTag 含「不列主進場」→ 應從 bucket 排除，rejected 含 TRADABILITY_TAG_BLOCK
        FinalDecisionCandidateRequest c = candidate("8064", new BigDecimal("9.0"),
                "題材指標，不列主進場");
        FinalDecisionResponse r = engine.evaluate(
                new FinalDecisionEvaluateRequest("A", "NONE", "EARLY", false, List.of(c)),
                MarketSession.LIVE_TRADING);

        assertEquals("REST", r.decision(), "唯一候選被 tag 攔下，應 REST");
        assertTrue(
                r.rejectedReasons().stream().anyMatch(s -> s.contains("TRADABILITY_TAG_BLOCK")),
                "rejected 應含 TRADABILITY_TAG_BLOCK：" + r.rejectedReasons());
        assertTrue(
                r.rejectedReasons().stream().anyMatch(s -> s.contains("不列主進場")),
                "rejected 訊息應提及原始 tag：" + r.rejectedReasons());
        assertEquals(0, r.selectedStocks().size(), "被 tag 攔下不應出現在 selectedStocks");
    }

    @Test
    void tagSoftPenalty_scoreReducedByOne_stillCanBucket() {
        // tradabilityTag 含「漲幅過大」+ score=7.0 → 軟懲罰 -1.0 → 6.0；6.0 < A(7.5) 但 >= B(6.5) 嗎？
        // 注意：本 case 是「6.0 < B」案，因為 mainStream=true 會 +0.3 boost → 6.3 仍 < 6.5 (B) → C
        // 為了專注於 score 確實被減 1（test 描述「still can bucket」），改 score=8.0 → 7.0 → A bucket
        FinalDecisionCandidateRequest c = candidate("3017", new BigDecimal("8.0"),
                "漲幅過大，僅參考");
        FinalDecisionResponse r = engine.evaluate(
                new FinalDecisionEvaluateRequest("A", "NONE", "EARLY", false, List.of(c)),
                MarketSession.LIVE_TRADING);

        // 8.0 - 1.0 = 7.0；+ mainStream 0.3 = 7.3；< A(7.5) 但 >= B(6.5) → ENTER (B trial)
        assertEquals("ENTER", r.decision(),
                "軟懲罰後仍應可進 B bucket：rejected=" + r.rejectedReasons() + " summary=" + r.summary());
        assertEquals(1, r.selectedStocks().size());
        assertEquals("3017", r.selectedStocks().get(0).stockCode());
        // 不應該被 hard block
        assertTrue(
                r.rejectedReasons().stream().noneMatch(s -> s.contains("TRADABILITY_TAG_BLOCK")),
                "軟懲罰不應觸發 hard block：" + r.rejectedReasons());
    }

    @Test
    void noTag_unaffected() {
        // tradabilityTag = null → 完全不影響原有流程
        FinalDecisionCandidateRequest c = candidate("2330", new BigDecimal("9.0"), null);
        FinalDecisionResponse r = engine.evaluate(
                new FinalDecisionEvaluateRequest("A", "NONE", "EARLY", false, List.of(c)),
                MarketSession.LIVE_TRADING);

        assertEquals("ENTER", r.decision(), "無 tag 應正常進場");
        assertEquals(1, r.selectedStocks().size());
        assertEquals("2330", r.selectedStocks().get(0).stockCode());
        assertTrue(
                r.rejectedReasons().stream().noneMatch(s -> s.contains("TRADABILITY_TAG")),
                "無 tag 不應出現任何 TRADABILITY_TAG 訊息");
    }

    @Test
    void flagDisabled_tagIgnored() {
        // feature flag = false → tag 完全失效，「不列主進場」標的仍能進場
        when(config.getBoolean(eq("final_decision.respect_tradability_tag.enabled"), anyBoolean()))
                .thenReturn(false);

        FinalDecisionCandidateRequest c = candidate("8064", new BigDecimal("9.0"),
                "題材指標，不列主進場");
        FinalDecisionResponse r = engine.evaluate(
                new FinalDecisionEvaluateRequest("A", "NONE", "EARLY", false, List.of(c)),
                MarketSession.LIVE_TRADING);

        assertEquals("ENTER", r.decision(), "flag=false 時 tag block 應失效");
        assertEquals(1, r.selectedStocks().size());
        assertEquals("8064", r.selectedStocks().get(0).stockCode());
        assertTrue(
                r.rejectedReasons().stream().noneMatch(s -> s.contains("TRADABILITY_TAG_BLOCK")),
                "flag=false 時不應有 TRADABILITY_TAG_BLOCK：" + r.rejectedReasons());
    }

    @Test
    void softPenaltyDoesNotKickToB_ifWasJustB() {
        // 邊界 case：原 score=6.55 + mainStream 0.3 = 6.85 (B 邊界 6.5+) → 軟懲罰 -1.0 後
        // 6.55 - 1.0 = 5.55；+ 0.3 = 5.85 < 6.5 → C → REST
        FinalDecisionCandidateRequest c = candidate("2337", new BigDecimal("6.55"),
                "漲幅過大，僅參考");
        FinalDecisionResponse r = engine.evaluate(
                new FinalDecisionEvaluateRequest("A", "NONE", "EARLY", false, List.of(c)),
                MarketSession.LIVE_TRADING);

        assertEquals("REST", r.decision(),
                "原本邊界 B 的候選經軟懲罰應跌至 C，REST：" + r.summary());
        assertEquals(0, r.selectedStocks().size());
        // 不應該被 hard block，僅應因為 score 過低被淘汰
        assertTrue(
                r.rejectedReasons().stream().noneMatch(s -> s.contains("TRADABILITY_TAG_BLOCK")),
                "軟懲罰路徑不應觸發 hard block：" + r.rejectedReasons());
        assertNotNull(r.rejectedReasons(), "rejected 應記錄被淘汰原因");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private FinalDecisionCandidateRequest candidate(String code, BigDecimal finalRankScore,
                                                     String tradabilityTag) {
        // 用最完整的 41-arg ctor 直接帶 tradabilityTag。
        return new FinalDecisionCandidateRequest(
                code,
                "TEST-" + code,
                "VALUE_FAIR",
                "BREAKOUT",
                /*riskRewardRatio*/ 2.5,
                /*includeInFinalPlan*/ true,
                /*mainStream*/ true,
                /*falseBreakout*/ false,
                /*belowOpen*/ false,
                /*belowPrevClose*/ false,
                /*nearDayHigh*/ false,
                /*stopLossReasonable*/ true,
                "tradability-tag-test",
                "100-102",
                98.0, 108.0, 115.0,
                /*javaStructureScore*/ null,
                /*claudeScore*/ null,
                /*codexScore*/ null,
                finalRankScore,
                /*isVetoed*/ false,
                /*baseScore*/ null,
                /*hasTheme*/ true,
                /*themeRank*/ 1,
                /*finalThemeScore*/ null,
                /*consensusScore*/ null,
                /*disagreementPenalty*/ null,
                /*volumeSpike*/ null,
                /*priceNotBreakHigh*/ null,
                /*entryTooExtended*/ false,
                /*entryTriggered*/ true,
                /*currentPrice*/ null,
                /*openPrice*/ null,
                /*previousClose*/ null,
                /*vwapPrice*/ null,
                /*volumeRatio*/ null,
                /*distanceFromOpenPct*/ null,
                /*dropFromPrevClosePct*/ null,
                /*marketRegime*/ null,
                /*dayHigh*/ null,
                tradabilityTag
        );
    }
}
