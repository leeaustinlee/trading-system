package com.austin.trading.controller;

import com.austin.trading.dto.request.PromotionReviewDecisionRequest;
import com.austin.trading.dto.response.PromotionGraduationReadinessResponse;
import com.austin.trading.dto.response.PromotionPolicySimulationResponse;
import com.austin.trading.dto.response.PromotionReviewResponse;
import com.austin.trading.dto.response.PromotionValidationReportResponse;
import com.austin.trading.service.PromotionReviewService;
import com.austin.trading.service.PromotionValidationDailySummaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PromotionReviewControllerTest {
    private static final LocalDate DATE = LocalDate.of(2026, 5, 22);
    private PromotionReviewService service;
    private PromotionValidationDailySummaryService dailySummaryService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(PromotionReviewService.class);
        dailySummaryService = mock(PromotionValidationDailySummaryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PromotionReviewController(service, null, dailySummaryService)).build();
    }

    @Test
    void buildAndQueueExposeReviewOnlySafetyBoundary() throws Exception {
        var response = PromotionReviewResponse.of(DATE, List.of(item(1L, "2375", "EXPLAIN_MISS", "PENDING_REVIEW")));
        when(service.build(DATE)).thenReturn(response);
        when(service.queue(DATE)).thenReturn(response);

        mvc.perform(post("/api/promotion-review/build").param("date", DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewOnly", is(true)))
                .andExpect(jsonPath("$.doesNotAffectFinalDecision", is(true)))
                .andExpect(jsonPath("$.doesNotAffectBuySellEnter", is(true)))
                .andExpect(jsonPath("$.doesNotWriteCandidateStock", is(true)))
                .andExpect(jsonPath("$.candidatePoolShadowIsNotTradable", is(true)))
                .andExpect(jsonPath("$.noAutoPromotion", is(true)))
                .andExpect(jsonPath("$.items[0].symbol", is("2375")));

        mvc.perform(get("/api/promotion-review/queue").param("date", DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].source", is("EXPLAIN_MISS")));
    }

    @Test
    void itemDecisionAndAuditEndpointsWork() throws Exception {
        var decided = item(2L, "2492", "PEER_SHADOW", "CANDIDATE_POOL_SHADOW");
        when(service.item(2L)).thenReturn(decided);
        when(service.decide(eq(2L), any(PromotionReviewDecisionRequest.class))).thenReturn(decided);
        when(service.audit(DATE, "2492")).thenReturn(PromotionReviewResponse.AuditResponse.of(DATE, "2492", List.of(
                new PromotionReviewResponse.AuditItem(1L, 2L, DATE, "2492", "PENDING_REVIEW", "CANDIDATE_POOL_SHADOW",
                        "APPROVE_SHADOW", "Austin", "still shadow", "{}", null, PromotionReviewResponse.defaultSafetyBoundary()))));

        mvc.perform(get("/api/promotion-review/item/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notFinalDecisionEligible", is(true)));

        mvc.perform(post("/api/promotion-review/item/2/decision")
                        .contentType("application/json")
                        .content("{\"status\":\"CANDIDATE_POOL_SHADOW\",\"reviewer\":\"Austin\",\"reason\":\"still shadow\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStatus", is("CANDIDATE_POOL_SHADOW")))
                .andExpect(jsonPath("$.tradable", is(false)))
                .andExpect(jsonPath("$.safetyBoundary.promotionRequiresSeparateRiskGate", is(true)));

        mvc.perform(get("/api/promotion-review/audit").param("date", DATE.toString()).param("symbol", "2492"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewOnly", is(true)))
                .andExpect(jsonPath("$.audits[0].action", is("APPROVE_SHADOW")));
    }

    @Test
    void decisionApiRejectsForbiddenTradingStatuses() throws Exception {
        when(service.decide(eq(2L), any(PromotionReviewDecisionRequest.class)))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "forbidden"));

        mvc.perform(post("/api/promotion-review/item/2/decision")
                        .contentType("application/json")
                        .content("{\"status\":\"BUY\",\"reviewer\":\"system\",\"reason\":\"forbidden\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void policySimulationEndpointExposesSimulationSafetyFlags() throws Exception {
        var response = PromotionPolicySimulationResponse.of(DATE, DATE.plusDays(1), "CANDIDATE_POOL_SHADOW",
                new PromotionPolicySimulationResponse.Summary(1, 1, 0, new BigDecimal("1.0"), new BigDecimal("2.0"),
                        new BigDecimal("3.0"), BigDecimal.ONE, 0, new BigDecimal("-1.0"), 0, 0),
                List.of(new PromotionPolicySimulationResponse.Item(1L, DATE, "2492", "華新科", "被動元件/MLCC", "PEER_SHADOW",
                        "CANDIDATE_POOL_SHADOW", "ELIGIBLE_FOR_SOFT_BOOST_SHADOW", new BigDecimal("1.0"),
                        new BigDecimal("2.0"), new BigDecimal("3.0"), new BigDecimal("-1.0"), false,
                        false, false, null)));
        when(service.policySimulation(DATE, DATE.plusDays(1), "CANDIDATE_POOL_SHADOW")).thenReturn(response);

        mvc.perform(get("/api/promotion-review/policy-simulation")
                        .param("startDate", DATE.toString())
                        .param("endDate", DATE.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulationOnly", is(true)))
                .andExpect(jsonPath("$.reviewOnly", is(true)))
                .andExpect(jsonPath("$.doesNotAffectFinalDecision", is(true)))
                .andExpect(jsonPath("$.doesNotAffectBuySellEnter", is(true)))
                .andExpect(jsonPath("$.doesNotWriteCandidateStock", is(true)))
                .andExpect(jsonPath("$.doesNotWriteProductionScore", is(true)))
                .andExpect(jsonPath("$.noAutoPromotion", is(true)))
                .andExpect(jsonPath("$.boundedSoftBoostShadowOnly", is(true)))
                .andExpect(jsonPath("$.summary.itemCount", is(1)))
                .andExpect(jsonPath("$.items[0].suggestedPolicy", is("ELIGIBLE_FOR_SOFT_BOOST_SHADOW")));

        verify(service).policySimulation(DATE, DATE.plusDays(1), "CANDIDATE_POOL_SHADOW");
    }

    @Test
    void validationReportEndpointExposesGraduationStatusAndSafetyFlags() throws Exception {
        var response = PromotionValidationReportResponse.of(DATE, DATE.plusDays(1), "CANDIDATE_POOL_SHADOW",
                new PromotionValidationReportResponse.GraduationCriteria(10, new BigDecimal("0.55"), BigDecimal.ZERO,
                        new BigDecimal("0.25"), new BigDecimal("-8")),
                new PromotionValidationReportResponse.Summary(1, 0, 1, 0, 0,
                        null, null, null, null, "BLOCKED_BY_DATA_GAP", "insufficient completed forward-return evidence or missing market data"),
                List.of(new PromotionValidationReportResponse.Item(1L, DATE, "2492", "華新科", "被動元件/MLCC", "PEER_SHADOW",
                        "CANDIDATE_POOL_SHADOW", "BLOCKED_BY_DATA_GAP", "PENDING_FORWARD_RETURN_BACKFILL",
                        null, null, null, null, null, "PENDING_FORWARD_RETURN_BACKFILL")));
        when(service.validationReport(DATE, DATE.plusDays(1), "CANDIDATE_POOL_SHADOW")).thenReturn(response);

        mvc.perform(get("/api/promotion-review/validation-report")
                        .param("startDate", DATE.toString())
                        .param("endDate", DATE.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validationOnly", is(true)))
                .andExpect(jsonPath("$.doesNotAffectFinalDecision", is(true)))
                .andExpect(jsonPath("$.doesNotAffectBuySellEnter", is(true)))
                .andExpect(jsonPath("$.doesNotWriteCandidateStock", is(true)))
                .andExpect(jsonPath("$.doesNotWriteProductionScore", is(true)))
                .andExpect(jsonPath("$.noAutoPromotion", is(true)))
                .andExpect(jsonPath("$.softBoostShadowOnly", is(true)))
                .andExpect(jsonPath("$.summary.overallStatus", is("BLOCKED_BY_DATA_GAP")))
                .andExpect(jsonPath("$.graduationCriteria.minSample", is(10)))
                .andExpect(jsonPath("$.items[0].validationStatus", is("BLOCKED_BY_DATA_GAP")));

        verify(service).validationReport(DATE, DATE.plusDays(1), "CANDIDATE_POOL_SHADOW");
    }

    @Test
    void forwardTrackingBridgeEndpointCreatesTrackingRowsWithSafetyFlags() throws Exception {
        Map<String, Object> response = Map.of(
                "trackingBridgeOnly", true,
                "doesNotAffectFinalDecision", true,
                "doesNotAffectBuySellEnter", true,
                "doesNotWriteCandidateStock", true,
                "doesNotWriteProductionScore", true,
                "noAutoPromotion", true,
                "sourceItems", 1,
                "written", 1,
                "skippedExisting", 0,
                "returnBackfillRequired", true);
        when(service.bridgeForwardTracking(DATE, DATE.plusDays(1), "CANDIDATE_POOL_SHADOW")).thenReturn(response);

        mvc.perform(post("/api/promotion-review/forward-tracking-bridge")
                        .param("startDate", DATE.toString())
                        .param("endDate", DATE.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingBridgeOnly", is(true)))
                .andExpect(jsonPath("$.doesNotAffectFinalDecision", is(true)))
                .andExpect(jsonPath("$.doesNotAffectBuySellEnter", is(true)))
                .andExpect(jsonPath("$.doesNotWriteCandidateStock", is(true)))
                .andExpect(jsonPath("$.doesNotWriteProductionScore", is(true)))
                .andExpect(jsonPath("$.noAutoPromotion", is(true)))
                .andExpect(jsonPath("$.written", is(1)))
                .andExpect(jsonPath("$.returnBackfillRequired", is(true)));

        verify(service).bridgeForwardTracking(DATE, DATE.plusDays(1), "CANDIDATE_POOL_SHADOW");
    }

    @Test
    void dailyValidationSummaryEndpointRunsReportOnlyAutomation() throws Exception {
        Map<String, Object> response = Map.of(
                "dailyValidationSummaryOnly", true,
                "reportOnly", true,
                "doesNotAffectFinalDecision", true,
                "doesNotAffectBuySellEnter", true,
                "doesNotWriteCandidateStock", true,
                "doesNotWriteProductionScore", true,
                "noAutoPromotion", true,
                "softBoostShadowOnly", true,
                "overallStatus", "BLOCKED_BY_DATA_GAP",
                "itemCount", 1);
        when(dailySummaryService.run(DATE, "CANDIDATE_POOL_SHADOW", 14)).thenReturn(response);

        mvc.perform(post("/api/promotion-review/daily-validation-summary")
                        .param("date", DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyValidationSummaryOnly", is(true)))
                .andExpect(jsonPath("$.reportOnly", is(true)))
                .andExpect(jsonPath("$.doesNotAffectFinalDecision", is(true)))
                .andExpect(jsonPath("$.doesNotAffectBuySellEnter", is(true)))
                .andExpect(jsonPath("$.doesNotWriteCandidateStock", is(true)))
                .andExpect(jsonPath("$.doesNotWriteProductionScore", is(true)))
                .andExpect(jsonPath("$.noAutoPromotion", is(true)))
                .andExpect(jsonPath("$.softBoostShadowOnly", is(true)))
                .andExpect(jsonPath("$.overallStatus", is("BLOCKED_BY_DATA_GAP")));

        verify(dailySummaryService).run(DATE, "CANDIDATE_POOL_SHADOW", 14);
    }

    @Test
    void dailyValidationSummaryBackfillEndpointRunsDateRangeReportOnlyAutomation() throws Exception {
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("dailyValidationSummaryBackfillOnly", true);
        response.put("reportOnly", true);
        response.put("doesNotAffectFinalDecision", true);
        response.put("doesNotAffectBuySellEnter", true);
        response.put("doesNotWriteCandidateStock", true);
        response.put("doesNotWriteProductionScore", true);
        response.put("noAutoPromotion", true);
        response.put("softBoostShadowOnly", true);
        response.put("totalDays", 2);
        response.put("daysWithItems", 1);
        response.put("totalItems", 1);
        when(dailySummaryService.backfill(DATE, DATE.plusDays(1), "CANDIDATE_POOL_SHADOW", 14)).thenReturn(response);

        mvc.perform(post("/api/promotion-review/daily-validation-summary/backfill")
                        .param("startDate", DATE.toString())
                        .param("endDate", DATE.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyValidationSummaryBackfillOnly", is(true)))
                .andExpect(jsonPath("$.reportOnly", is(true)))
                .andExpect(jsonPath("$.doesNotAffectFinalDecision", is(true)))
                .andExpect(jsonPath("$.doesNotWriteCandidateStock", is(true)))
                .andExpect(jsonPath("$.doesNotWriteProductionScore", is(true)))
                .andExpect(jsonPath("$.noAutoPromotion", is(true)))
                .andExpect(jsonPath("$.softBoostShadowOnly", is(true)))
                .andExpect(jsonPath("$.totalDays", is(2)))
                .andExpect(jsonPath("$.daysWithItems", is(1)));

        verify(dailySummaryService).backfill(DATE, DATE.plusDays(1), "CANDIDATE_POOL_SHADOW", 14);
    }

    @Test
    void graduationReadinessEndpointExposesReportOnlyThresholdTuningBoundary() throws Exception {
        var criteria = new PromotionValidationReportResponse.GraduationCriteria(10, new BigDecimal("0.55"),
                BigDecimal.ZERO, new BigDecimal("0.25"), new BigDecimal("-8"));
        var summary = new PromotionGraduationReadinessResponse.ReadinessSummary("BLOCKED_BY_DATA_GAP",
                "need more completed forward returns", 1, 10, 9, 1, 0, 0, 0,
                new BigDecimal("1.0"), BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("-2.0"),
                new BigDecimal("0.55"), BigDecimal.ZERO, new BigDecimal("0.25"), new BigDecimal("-8"));
        var suggestion = new PromotionGraduationReadinessResponse.ThresholdSuggestion("promotion.validation.min_sample",
                "10", "10", "KEEP", "collect more forward tracking", true, true);
        var item = new PromotionGraduationReadinessResponse.Item(1L, DATE, "2492", "華新科", "被動元件/MLCC",
                "PEER_SHADOW", "CANDIDATE_POOL_SHADOW", "ELIGIBLE_FOR_SOFT_BOOST_SHADOW",
                "CANDIDATE_FOR_SHADOW_REVIEW", "positive forward evidence; still requires manual shadow review",
                new BigDecimal("1.0"), new BigDecimal("-2.0"), false, null);
        var response = PromotionGraduationReadinessResponse.of(DATE, DATE.plusDays(1), "CANDIDATE_POOL_SHADOW",
                criteria, summary, List.of(suggestion), List.of(item));
        when(service.graduationReadiness(DATE, DATE.plusDays(1), "CANDIDATE_POOL_SHADOW")).thenReturn(response);

        mvc.perform(get("/api/promotion-review/graduation-readiness")
                        .param("startDate", DATE.toString())
                        .param("endDate", DATE.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readinessReportOnly", is(true)))
                .andExpect(jsonPath("$.thresholdTuningSuggestionOnly", is(true)))
                .andExpect(jsonPath("$.doesNotAffectFinalDecision", is(true)))
                .andExpect(jsonPath("$.doesNotAffectBuySellEnter", is(true)))
                .andExpect(jsonPath("$.doesNotWriteCandidateStock", is(true)))
                .andExpect(jsonPath("$.doesNotWriteProductionScore", is(true)))
                .andExpect(jsonPath("$.noThresholdMutation", is(true)))
                .andExpect(jsonPath("$.summary.readinessStatus", is("BLOCKED_BY_DATA_GAP")))
                .andExpect(jsonPath("$.thresholdSuggestions[0].manualReviewRequired", is(true)))
                .andExpect(jsonPath("$.thresholdSuggestions[0].appliesToShadowOnly", is(true)));

        verify(service).graduationReadiness(DATE, DATE.plusDays(1), "CANDIDATE_POOL_SHADOW");
    }

    private PromotionReviewResponse.Item item(Long id, String symbol, String source, String status) {
        return new PromotionReviewResponse.Item(id, DATE, symbol, symbol.equals("2375") ? "凱美" : "華新科", "被動元件/MLCC", source,
                "PEER_SHADOW", status, null, "review only", new BigDecimal("10"), false, false, "MAINSTREAM",
                new BigDecimal("8"), BigDecimal.ZERO, new BigDecimal("24"), BigDecimal.ONE, "radar_watch_only", null, null,
                null, status.equals("CANDIDATE_POOL_SHADOW") ? "CANDIDATE_POOL_SHADOW" : "WATCH_ONLY", false, true,
                PromotionReviewResponse.defaultSafetyBoundary(), Map.of("reviewOnly", true), "{}");
    }
}
