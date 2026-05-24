package com.austin.trading.controller;

import com.austin.trading.dto.response.ResearchUniverseResponse;
import com.austin.trading.dto.response.ThemeReplayMetricsResponse;
import com.austin.trading.dto.response.ThemeReplayMetricsResponse;
import com.austin.trading.service.ResearchUniverseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ResearchUniverseControllerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 22);
    private ResearchUniverseService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(ResearchUniverseService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ResearchUniverseController(service)).build();
    }

    @Test
    void returnsResearchUniverseItemsWithSafetyBoundary() throws Exception {
        when(service.get(DATE)).thenReturn(response(List.of(item("2327", "LEADERSHIP_ONLY"), item("2492", "PEER_SHADOW"))));

        mockMvc.perform(get("/api/research-universe").param("date", "2026-05-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shadowOnly").value(true))
                .andExpect(jsonPath("$.researchOnly").value(true))
                .andExpect(jsonPath("$.safetyBoundary.doesNotAffectFinalDecision").value(true))
                .andExpect(jsonPath("$.safetyBoundary.doesNotAffectBuySellEnter").value(true))
                .andExpect(jsonPath("$.safetyBoundary.researchUniverseNotTradable").value(true))
                .andExpect(jsonPath("$.safetyBoundary.promotionReviewRequired").value(true))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].researchUniverse").value(true))
                .andExpect(jsonPath("$.items[0].tradableUniverse").value(false))
                .andExpect(jsonPath("$.items[0].governanceStatus").value("SHADOW_ONLY"));
    }

    @Test
    void returnsThemeSymbolAndGovernanceSummaryEndpoints() throws Exception {
        when(service.byTheme(DATE, "被動元件")).thenReturn(response(List.of(item("2327", "LEADERSHIP_ONLY"))));
        when(service.bySymbol(DATE, "2327")).thenReturn(response(List.of(item("2327", "LEADERSHIP_ONLY"))));
        when(service.governanceSummary(DATE)).thenReturn(new ResearchUniverseResponse.GovernanceSummary(
                DATE, 1, 1, 0, 0,
                Map.of("SHADOW_ONLY", 1L),
                Map.of("LEADERSHIP_ONLY", 1L),
                ResearchUniverseResponse.SafetyBoundary.researchOnlyBoundary(), true, true));

        mockMvc.perform(get("/api/research-universe/themes/{themeTag}", "被動元件").param("date", "2026-05-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].themeTag").value("被動元件"));
        mockMvc.perform(get("/api/research-universe/symbol/{symbol}", "2327").param("date", "2026-05-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].symbol").value("2327"));
        mockMvc.perform(get("/api/research-universe/governance-summary").param("date", "2026-05-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradableUniverseCount").value(0))
                .andExpect(jsonPath("$.promotedToTradableCount").value(0))
                .andExpect(jsonPath("$.governanceStatusCounts.SHADOW_ONLY").value(1));
    }

    private ResearchUniverseResponse response(List<ResearchUniverseResponse.Item> items) {
        return new ResearchUniverseResponse(DATE, true, true, ResearchUniverseResponse.SafetyBoundary.researchOnlyBoundary(),
                ThemeReplayMetricsResponse.MetricsSummary.empty(), items);
    }

    private ResearchUniverseResponse.Item item(String symbol, String role) {
        boolean leader = "2327".equals(symbol);
        return new ResearchUniverseResponse.Item(
                symbol,
                leader ? "國巨" : "華新科",
                "被動元件",
                role,
                leader ? "leadership" : "peer_shadow",
                new BigDecimal("9.0"),
                new BigDecimal("9.0"),
                new BigDecimal("1.0"),
                null,
                "MAINSTREAM",
                new BigDecimal("0.70"),
                "advisory-only; lifecycle does not promote research universe or override risk gate",
                "SHADOW_ONLY",
                true,
                false,
                false,
                null,
                "research only",
                leader ? "THEME_LEADER" : "PEER_SHADOW_CONTEXT",
                "2327",
                leader,
                false,
                "research-only",
                "{}",
                ResearchUniverseResponse.SafetyBoundary.researchOnlyBoundary()
        );
    }
}
