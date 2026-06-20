package com.austin.trading.controller;

import com.austin.trading.dto.response.LifecyclePullbackPlanShadowResponse;
import com.austin.trading.service.LifecyclePullbackPlanShadowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LifecyclePullbackPlanShadowControllerTest {
    private FakeLifecyclePullbackPlanShadowService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = new FakeLifecyclePullbackPlanShadowService();
        mockMvc = MockMvcBuilders.standaloneSetup(new LifecyclePullbackPlanShadowController(service)).build();
    }

    @Test
    void shadowEndpointReturnsReadOnlySafetyFieldsAndRows() throws Exception {
        service.response = response(60);

        mockMvc.perform(get("/api/theme-lifecycle/pullback-plan/shadow").param("days", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.shadowOnly").value(true))
                .andExpect(jsonPath("$.doesNotAffectBuySell").value(true))
                .andExpect(jsonPath("$.doesNotWriteCandidateWatchlist").value(true))
                .andExpect(jsonPath("$.doesNotAffectRanking").value(true))
                .andExpect(jsonPath("$.requestedDays").value(60))
                .andExpect(jsonPath("$.rows[0].symbol").value("1001"))
                .andExpect(jsonPath("$.rows[0].planStatus").value("AVOID_CHASING"))
                .andExpect(jsonPath("$.byStatus[0].planStatus").value("AVOID_CHASING"));

        assertThat(service.days).isEqualTo(60);
    }

    private LifecyclePullbackPlanShadowResponse response(int days) {
        LocalDate end = LocalDate.of(2026, 6, 20);
        LocalDate start = end.minusDays(days - 1L);
        var item = new LifecyclePullbackPlanShadowResponse.Item(
                end, "1001", "Stock1001", "AI", "OVERHEATED", new BigDecimal("0.8000"),
                5, 12, 3, new BigDecimal("0.9000"), new BigDecimal("0.2500"), true,
                "NEAR_LIMIT", true, false, false, "WATCHLIST_SHADOW", "AVOID_CHASING",
                "SHADOW_ONLY:AVOID_CHASING; doesNotAffectBuySell=true", new BigDecimal("6.0000"),
                new BigDecimal("9.0000"), new BigDecimal("-4.0000"), "ACTIVE");
        return LifecyclePullbackPlanShadowResponse.of(
                days, start, end, 1, 1, 1, 0, 0,
                new BigDecimal("6.0000"), new BigDecimal("9.0000"), new BigDecimal("-4.0000"),
                List.of(new LifecyclePullbackPlanShadowResponse.StatusSummary(
                        "AVOID_CHASING", 1, new BigDecimal("6.0000"), new BigDecimal("9.0000"), new BigDecimal("-4.0000"))),
                List.of(new LifecyclePullbackPlanShadowResponse.StageSummary(
                        "OVERHEATED", 1, 1, 1, new BigDecimal("0.2500"), new BigDecimal("6.0000"), new BigDecimal("-4.0000"))),
                List.of(item), List.of());
    }

    private static class FakeLifecyclePullbackPlanShadowService extends LifecyclePullbackPlanShadowService {
        private LifecyclePullbackPlanShadowResponse response;
        private int days;

        private FakeLifecyclePullbackPlanShadowService() {
            super(null, null, null);
        }

        @Override
        public LifecyclePullbackPlanShadowResponse report(int days) {
            this.days = days;
            return response;
        }
    }
}
