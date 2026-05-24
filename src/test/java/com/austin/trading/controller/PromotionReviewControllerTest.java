package com.austin.trading.controller;

import com.austin.trading.dto.request.PromotionReviewDecisionRequest;
import com.austin.trading.dto.response.PromotionReviewResponse;
import com.austin.trading.service.PromotionReviewService;
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
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(PromotionReviewService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PromotionReviewController(service)).build();
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

    private PromotionReviewResponse.Item item(Long id, String symbol, String source, String status) {
        return new PromotionReviewResponse.Item(id, DATE, symbol, symbol.equals("2375") ? "凱美" : "華新科", "被動元件/MLCC", source,
                "PEER_SHADOW", status, null, "review only", new BigDecimal("10"), false, false, "MAINSTREAM",
                new BigDecimal("8"), BigDecimal.ZERO, new BigDecimal("24"), BigDecimal.ONE, "radar_watch_only", null, null,
                null, status.equals("CANDIDATE_POOL_SHADOW") ? "CANDIDATE_POOL_SHADOW" : "WATCH_ONLY", false, true,
                PromotionReviewResponse.defaultSafetyBoundary(), Map.of("reviewOnly", true), "{}");
    }
}
