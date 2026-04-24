package com.austin.trading.service;

import com.austin.trading.dto.internal.ExecutionTimingDecision;
import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.PortfolioRiskDecision;
import com.austin.trading.dto.internal.RankedCandidate;
import com.austin.trading.dto.internal.ThemeStrengthDecision;
import com.austin.trading.dto.request.CodexResultPayloadRequest;
import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import com.austin.trading.dto.request.MarketSnapshotCreateRequest;
import com.austin.trading.dto.request.TradingStateUpsertRequest;
import com.austin.trading.dto.response.FinalDecisionResponse;
import com.austin.trading.dto.response.MarketCurrentResponse;
import com.austin.trading.dto.response.TradingStateResponse;
import com.austin.trading.entity.AiTaskEntity;
import com.austin.trading.entity.FinalDecisionEntity;
import com.austin.trading.repository.AiTaskRepository;
import com.austin.trading.repository.FinalDecisionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("integration")
class FinalDecisionExecutionRealityReplayIntegrationTests {

    private static final AtomicInteger DATE_SEQ = new AtomicInteger(0);

    @Autowired private FinalDecisionService finalDecisionService;
    @Autowired private FinalDecisionRepository finalDecisionRepository;
    @Autowired private AiTaskRepository aiTaskRepository;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private MarketDataService marketDataService;
    @MockBean private TradingStateService tradingStateService;
    @MockBean private CandidateScanService candidateScanService;
    @MockBean private CapitalService capitalService;
    @MockBean private MarketCooldownService marketCooldownService;
    @MockBean private MarketRegimeService marketRegimeService;
    @MockBean private StockRankingService stockRankingService;
    @MockBean private SetupValidationService setupValidationService;
    @MockBean private ExecutionTimingService executionTimingService;
    @MockBean private PortfolioRiskService portfolioRiskService;
    @MockBean private ExecutionDecisionService executionDecisionService;
    @MockBean private ThemeStrengthService themeStrengthService;
    @MockBean private MomentumDecisionService momentumDecisionService;
    @MockBean private com.austin.trading.repository.PositionRepository positionRepository;

    @BeforeEach
    void setUp() {
        when(marketDataService.getCurrentMarket()).thenReturn(Optional.of(
                new MarketCurrentResponse(1L, LocalDate.now(), "A", "OPENING", "WAIT", "{}", LocalDateTime.now())));
        when(marketDataService.getMarketPreferToday()).thenReturn(Optional.empty());
        when(marketDataService.createSnapshot(any(MarketSnapshotCreateRequest.class))).thenAnswer(inv -> {
            MarketSnapshotCreateRequest req = inv.getArgument(0);
            return new MarketCurrentResponse(1L, req.tradingDate(), req.marketGrade(),
                    req.marketPhase(), req.decision(), req.payloadJson(), LocalDateTime.now());
        });

        when(tradingStateService.getStateByDate(any(LocalDate.class))).thenReturn(Optional.of(
                new TradingStateResponse(1L, LocalDate.now(), "A", "NONE", "EARLY", "ON", "WATCH", "{}", LocalDateTime.now())));
        when(tradingStateService.create(any(TradingStateUpsertRequest.class))).thenAnswer(inv -> {
            TradingStateUpsertRequest req = inv.getArgument(0);
            return new TradingStateResponse(1L, req.tradingDate(), req.marketGrade(), req.decisionLock(),
                    req.timeDecayStage(), req.hourlyGate(), req.monitorMode(), req.payloadJson(), LocalDateTime.now());
        });

        when(capitalService.getAvailableCash()).thenReturn(BigDecimal.valueOf(500_000));
        when(marketCooldownService.check()).thenReturn(new MarketCooldownService.MarketCooldownResult(false, null));
        when(positionRepository.findByStatus("OPEN")).thenReturn(List.of());
        when(themeStrengthService.evaluateAll(any(LocalDate.class), any())).thenReturn(Map.of(
                "SEMI", new ThemeStrengthDecision(LocalDate.now(), "SEMI", new BigDecimal("9.0"),
                        "MID_TREND", "EARNINGS", true, new BigDecimal("1.5"), "{}")));
        when(setupValidationService.getValidByDate(any(LocalDate.class))).thenReturn(List.of());
        when(setupValidationService.evaluateAll(anyList())).thenAnswer(inv -> List.of());
        when(momentumDecisionService.evaluate(any(LocalDate.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(new MomentumDecisionService.MomentumResultBundle(List.of(), false, 0, 0));
        when(executionDecisionService.logDecisions(anyList(), any(LocalDate.class))).thenReturn(List.of());
        doNothing().when(positionRepository).deleteAll();
    }

    @Test
    void premarket_noTradeDecision_forcesWaitAndNeverEnter() throws Exception {
        LocalDate tradingDate = uniqueTradingDate();
        seedAiTask(tradingDate, "PREMARKET", "replay/premarket-no-trade.json");

        when(candidateScanService.loadFinalDecisionCandidates(eq(tradingDate), anyInt()))
                .thenReturn(List.of(candidate("2454", false, 2.0)));
        when(marketRegimeService.getLatestForToday()).thenReturn(Optional.of(bullRegime(tradingDate)));
        when(marketRegimeService.getLatest()).thenReturn(Optional.of(bullRegime(tradingDate)));
        when(portfolioRiskService.evaluatePortfolioGate(anyList(), eq(tradingDate)))
                .thenReturn(gateDecision(tradingDate, true, null));

        FinalDecisionResponse response = finalDecisionService.evaluateAndPersist(tradingDate);
        JsonNode payload = latestDecisionPayload(tradingDate);

        assertThat(response.decision()).isEqualTo("WAIT");
        assertThat(response.selectedStocks()).isEmpty();
        assertThat(response.summary()).contains("PREMARKET").contains("正式進場判斷");
        assertThat(payload.path("decision").asText()).isEqualTo("WAIT");
        assertThat(payload.path("selected_count").asInt()).isZero();
        assertThat(payload.path("planning").path("decisionTrace").path("finalAction").asText()).isEqualTo("WAIT");
        assertThat(payload.path("planning").path("decisionTrace").path("finalReason").asText())
                .contains("PREMARKET_BIAS_ONLY");
    }

    @Test
    void opening_selectBuyNow_getsExecutionPriorityAndEnters() throws Exception {
        LocalDate tradingDate = uniqueTradingDate();
        seedAiTask(tradingDate, "OPENING", "replay/opening-select-buy-now.json");

        FinalDecisionCandidateRequest raw = candidate("2454", false, 1.6);
        when(candidateScanService.loadFinalDecisionCandidates(eq(tradingDate), anyInt()))
                .thenReturn(List.of(raw));
        when(marketRegimeService.getLatestForToday()).thenReturn(Optional.of(bullRegime(tradingDate)));
        when(marketRegimeService.getLatest()).thenReturn(Optional.of(bullRegime(tradingDate)));
        when(portfolioRiskService.evaluatePortfolioGate(anyList(), eq(tradingDate)))
                .thenReturn(gateDecision(tradingDate, true, null));
        when(stockRankingService.rank(anyList(), eq(tradingDate), any(), any()))
                .thenReturn(List.of(ranked(tradingDate, "2454", false, false, "NOT_IN_FINAL_PLAN", "SEMI")));
        when(executionTimingService.evaluateAll(anyList(), eq(tradingDate)))
                .thenReturn(List.of(new ExecutionTimingDecision(
                        tradingDate, "2454", "BREAKOUT_CONTINUATION", false, "WAIT", "LOW",
                        false, 0, 0, "ENTRY_TOO_EARLY", "{}")));
        when(portfolioRiskService.evaluateCandidates(anyList(), anyList(), eq(tradingDate)))
                .thenReturn(List.of(candidateRisk(tradingDate, "2454", true, null)));

        FinalDecisionResponse response = finalDecisionService.evaluateAndPersist(tradingDate);
        JsonNode payload = latestDecisionPayload(tradingDate);
        JsonNode trace = payload.path("planning").path("decisionTrace");

        assertThat(response.decision()).isEqualTo("ENTER");
        assertThat(response.selectedStocks()).extracting("stockCode").containsExactly("2454");
        assertThat(trace.path("codexBucket").asText()).isEqualTo("SELECT_BUY_NOW");
        assertThat(trace.path("suggestedAction").asText()).isEqualTo("BUY_NOW");
        assertThat(trace.path("executionPriority").asBoolean()).isTrue();
        assertThat(trace.path("softPenaltySkipped").asBoolean()).isTrue();
        assertThat(trace.path("hardRiskBlocked").asBoolean()).isFalse();
        assertThat(trace.path("finalAction").asText()).isEqualTo("ENTER");
        // v2.9 Gate 6/7：fixture 2454 current=2200 > open=2188 > prev=2175 → 無價格弱勢，PASS
        JsonNode priceGate = trace.path("priceGate");
        assertThat(priceGate.isMissingNode()).isFalse();
        assertThat(priceGate.path("priceGateDecision").asText()).isEqualTo("PASS");
        assertThat(priceGate.path("priceGateHardBlock").asBoolean()).isFalse();
        assertThat(priceGate.path("belowOpen").asBoolean()).isFalse();
        assertThat(priceGate.path("belowPrevClose").asBoolean()).isFalse();
    }

    @Test
    void opening_waitPullback_neverDirectlyBuys_andTraceShowsReason() throws Exception {
        LocalDate tradingDate = uniqueTradingDate();
        seedAiTask(tradingDate, "OPENING", "replay/opening-wait-pullback.json");

        when(candidateScanService.loadFinalDecisionCandidates(eq(tradingDate), anyInt()))
                .thenReturn(List.of(candidate("2454", true, 0.9)));
        when(marketRegimeService.getLatestForToday()).thenReturn(Optional.of(bullRegime(tradingDate)));
        when(marketRegimeService.getLatest()).thenReturn(Optional.of(bullRegime(tradingDate)));
        when(portfolioRiskService.evaluatePortfolioGate(anyList(), eq(tradingDate)))
                .thenReturn(gateDecision(tradingDate, true, null));

        FinalDecisionResponse response = finalDecisionService.evaluateAndPersist(tradingDate);
        JsonNode trace = latestDecisionPayload(tradingDate).path("planning").path("decisionTrace");

        assertThat(response.decision()).isEqualTo("WAIT");
        assertThat(response.selectedStocks()).isEmpty();
        assertThat(trace.path("codexBucket").asText()).isEqualTo("SELECT_WAIT_PULLBACK");
        assertThat(trace.path("finalAction").asText()).isEqualTo("WAIT");
        assertThat(trace.path("finalReason").asText()).contains("Wait for pullback");
    }

    @Test
    void opening_rejectWeak_neverBuys_andTraceShowsCodexReject() throws Exception {
        LocalDate tradingDate = uniqueTradingDate();
        seedAiTask(tradingDate, "OPENING", "replay/opening-reject-weak.json");

        when(candidateScanService.loadFinalDecisionCandidates(eq(tradingDate), anyInt()))
                .thenReturn(List.of(candidate("2454", true, 0.7)));
        when(marketRegimeService.getLatestForToday()).thenReturn(Optional.of(rangeRegime(tradingDate)));
        when(marketRegimeService.getLatest()).thenReturn(Optional.of(rangeRegime(tradingDate)));
        when(portfolioRiskService.evaluatePortfolioGate(anyList(), eq(tradingDate)))
                .thenReturn(gateDecision(tradingDate, true, null));

        FinalDecisionResponse response = finalDecisionService.evaluateAndPersist(tradingDate);
        JsonNode trace = latestDecisionPayload(tradingDate).path("planning").path("decisionTrace");

        assertThat(response.decision()).isEqualTo("REST");
        assertThat(response.selectedStocks()).isEmpty();
        assertThat(trace.path("codexBucket").asText()).isEqualTo("REJECT_WEAK");
        assertThat(trace.path("finalAction").asText()).isEqualTo("REJECT");
        assertThat(trace.path("finalReason").asText()).contains("Lost open price");
    }

    /**
     * v2.9.1 Gate 6/7 強化：驗證 overlay → IntradayVwapService → VolumeProfileService → trace 整條鏈。
     *
     * <p>Fixture 提供 volume=15000 張、turnover=3.3e10 元、averageDailyVolume=28000 張；
     * VWAP ≈ turnover / (15000 × 1000) = 2200；volumeRatio 依時間取 linear-pace。
     * 因為 fixture 裡 currentPrice=2200 > open=2188 > prev=2175，belowOpen / belowPrevClose 均為 false，
     * priceGateDecision 應為 PASS，但 trace 必須含完整 vwap / volume 欄位供盤後檢討。</p>
     */
    @Test
    void opening_selectBuyNow_withVwapAndVolume_traceIncludesAllFields() throws Exception {
        LocalDate tradingDate = uniqueTradingDate();
        seedAiTask(tradingDate, "OPENING", "replay/opening-buy-now-with-vwap.json");

        FinalDecisionCandidateRequest raw = candidate("2454", false, 1.6);
        when(candidateScanService.loadFinalDecisionCandidates(eq(tradingDate), anyInt()))
                .thenReturn(List.of(raw));
        when(marketRegimeService.getLatestForToday()).thenReturn(Optional.of(bullRegime(tradingDate)));
        when(marketRegimeService.getLatest()).thenReturn(Optional.of(bullRegime(tradingDate)));
        when(portfolioRiskService.evaluatePortfolioGate(anyList(), eq(tradingDate)))
                .thenReturn(gateDecision(tradingDate, true, null));
        when(stockRankingService.rank(anyList(), eq(tradingDate), any(), any()))
                .thenReturn(List.of(ranked(tradingDate, "2454", false, false, "NOT_IN_FINAL_PLAN", "SEMI")));
        when(executionTimingService.evaluateAll(anyList(), eq(tradingDate)))
                .thenReturn(List.of(new ExecutionTimingDecision(
                        tradingDate, "2454", "BREAKOUT_CONTINUATION", false, "WAIT", "LOW",
                        false, 0, 0, "ENTRY_TOO_EARLY", "{}")));
        when(portfolioRiskService.evaluateCandidates(anyList(), anyList(), eq(tradingDate)))
                .thenReturn(List.of(candidateRisk(tradingDate, "2454", true, null)));

        finalDecisionService.evaluateAndPersist(tradingDate);
        JsonNode trace = latestDecisionPayload(tradingDate).path("planning").path("decisionTrace");
        JsonNode priceGate = trace.path("priceGate");

        // 基本 priceGate
        assertThat(priceGate.isMissingNode()).isFalse();
        assertThat(priceGate.path("priceGateDecision").asText()).isEqualTo("PASS");

        // VWAP 欄位（由 IntradayVwapService.computeFromCumulative 填）
        assertThat(priceGate.path("vwapSource").asText()).isEqualTo("cumulative-mis-turnover");
        assertThat(priceGate.path("vwapPrice").decimalValue())
                .isEqualByComparingTo(new BigDecimal("2200.0000"));

        // volumeRatio 欄位（由 VolumeProfileService.compute 填；ratio 依執行時間變，但 source/available/currentVolume 穩定）
        assertThat(priceGate.path("volumeSource").asText()).isEqualTo("linear-pace");
        assertThat(priceGate.path("currentVolume").asLong()).isEqualTo(15000L);
        assertThat(priceGate.path("avgDailyVolume").asLong()).isEqualTo(28000L);
        assertThat(priceGate.path("expectedVolume").asLong()).isGreaterThan(0L);
        assertThat(priceGate.path("turnover").asDouble()).isEqualTo(3.3e10);
    }

    @Test
    void opening_selectBuyNow_withHardRisk_isBlockedWithTrace() throws Exception {
        LocalDate tradingDate = uniqueTradingDate();
        seedAiTask(tradingDate, "OPENING", "replay/opening-buy-now-hard-risk-blocked.json");

        when(candidateScanService.loadFinalDecisionCandidates(eq(tradingDate), anyInt()))
                .thenReturn(List.of(candidate("2454", false, 1.6)));
        when(marketRegimeService.getLatestForToday()).thenReturn(Optional.of(bullRegime(tradingDate)));
        when(marketRegimeService.getLatest()).thenReturn(Optional.of(bullRegime(tradingDate)));
        when(portfolioRiskService.evaluatePortfolioGate(anyList(), eq(tradingDate)))
                .thenReturn(gateDecision(tradingDate, true, null));
        when(stockRankingService.rank(anyList(), eq(tradingDate), any(), any()))
                .thenReturn(List.of(ranked(tradingDate, "2454", false, false, "NOT_IN_FINAL_PLAN", "SEMI")));
        when(executionTimingService.evaluateAll(anyList(), eq(tradingDate)))
                .thenReturn(List.of(new ExecutionTimingDecision(
                        tradingDate, "2454", "BREAKOUT_CONTINUATION", false, "WAIT", "LOW",
                        false, 0, 0, "ENTRY_TOO_EARLY", "{}")));
        when(portfolioRiskService.evaluateCandidates(anyList(), anyList(), eq(tradingDate)))
                .thenReturn(List.of(candidateRisk(tradingDate, "2454", false, "THEME_OVER_EXPOSED")));

        FinalDecisionResponse response = finalDecisionService.evaluateAndPersist(tradingDate);
        JsonNode trace = latestDecisionPayload(tradingDate).path("planning").path("decisionTrace");

        assertThat(response.decision()).isEqualTo("REST");
        assertThat(response.selectedStocks()).isEmpty();
        assertThat(trace.path("codexBucket").asText()).isEqualTo("SELECT_BUY_NOW");
        assertThat(trace.path("executionPriority").asBoolean()).isTrue();
        assertThat(trace.path("hardRiskBlocked").asBoolean()).isTrue();
        assertThat(trace.path("finalAction").asText()).isEqualTo("BLOCKED");
        assertThat(trace.path("finalReason").asText()).contains("THEME_OVER_EXPOSED");
    }

    private LocalDate uniqueTradingDate() {
        return LocalDate.of(2035, 1, 1).plusDays(DATE_SEQ.incrementAndGet());
    }

    private void seedAiTask(LocalDate tradingDate, String taskType, String fixturePath) throws Exception {
        CodexResultPayloadRequest payload = readFixture(fixturePath);

        AiTaskEntity task = new AiTaskEntity();
        task.setTradingDate(tradingDate);
        task.setTaskType(taskType);
        task.setStatus(AiTaskService.STATUS_CODEX_DONE);
        task.setPromptSummary("replay");
        task.setCodexResultMarkdown("replay");
        task.setCodexPayloadJson(objectMapper.writeValueAsString(payload));
        task.setClaudeDoneAt(LocalDateTime.now().minusMinutes(5));
        task.setCodexDoneAt(LocalDateTime.now().minusMinutes(1));
        task.setLastTransitionReason("replay-seed");
        aiTaskRepository.save(task);
    }

    private CodexResultPayloadRequest readFixture(String fixturePath) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(fixturePath)) {
            assertThat(in).as("fixture " + fixturePath).isNotNull();
            return objectMapper.readValue(in, CodexResultPayloadRequest.class);
        }
    }

    private JsonNode latestDecisionPayload(LocalDate tradingDate) throws Exception {
        FinalDecisionEntity entity = finalDecisionRepository.findAll().stream()
                .filter(it -> tradingDate.equals(it.getTradingDate()))
                .reduce((first, second) -> second)
                .orElseThrow();
        return objectMapper.readTree(entity.getPayloadJson());
    }

    private FinalDecisionCandidateRequest candidate(String symbol, boolean includeInFinalPlan, double rr) {
        return new FinalDecisionCandidateRequest(
                symbol,
                "聯發科",
                "VALUE_FAIR",
                "BREAKOUT",
                rr,
                includeInFinalPlan,
                true,
                false,
                false,
                false,
                true,
                true,
                "Replay candidate",
                "2185-2205",
                2140.0,
                2300.0,
                2360.0,
                null,
                new BigDecimal("9.2"),
                null,
                null,
                false,
                new BigDecimal("8.8"),
                true,
                1,
                new BigDecimal("9.0"),
                null,
                null,
                false,
                false,
                false,
                false
        );
    }

    private RankedCandidate ranked(LocalDate tradingDate, String symbol, boolean vetoed,
                                   boolean eligible, String reason, String themeTag) {
        return new RankedCandidate(
                tradingDate,
                symbol,
                new BigDecimal("5.5"),
                new BigDecimal("8.8"),
                new BigDecimal("9.0"),
                new BigDecimal("9.1"),
                themeTag,
                vetoed,
                eligible,
                reason,
                "{}"
        );
    }

    private PortfolioRiskDecision gateDecision(LocalDate tradingDate, boolean approved, String reason) {
        return new PortfolioRiskDecision(tradingDate, null, approved, reason, 0, 3, null, null, "{}");
    }

    private PortfolioRiskDecision candidateRisk(LocalDate tradingDate, String symbol, boolean approved, String reason) {
        return new PortfolioRiskDecision(tradingDate, symbol, approved, reason, 0, 3, "SEMI", new BigDecimal("20"), "{}");
    }

    private MarketRegimeDecision bullRegime(LocalDate tradingDate) {
        return new MarketRegimeDecision(
                tradingDate,
                LocalDateTime.now(),
                "BULL_TREND",
                "A",
                true,
                new BigDecimal("1.0"),
                List.of("BREAKOUT_CONTINUATION", "PULLBACK_CONFIRMATION"),
                "Bull trend",
                "[]",
                "{}"
        );
    }

    private MarketRegimeDecision rangeRegime(LocalDate tradingDate) {
        return new MarketRegimeDecision(
                tradingDate,
                LocalDateTime.now(),
                "RANGE_CHOP",
                "A",
                true,
                new BigDecimal("1.0"),
                List.of("BREAKOUT_CONTINUATION", "PULLBACK_CONFIRMATION"),
                "Range chop",
                "[]",
                "{}"
        );
    }
}
