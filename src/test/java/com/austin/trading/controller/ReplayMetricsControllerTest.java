package com.austin.trading.controller;

import com.austin.trading.dto.response.ThemeReplayMetricsResponse;
import com.austin.trading.service.ReplayMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReplayMetricsControllerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 22);
    private ReplayMetricsService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ReplayMetricsService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ReplayMetricsController(service)).build();
    }

    @Test
    void getMetricsReturnsReplayAnalyticsSafetyBoundary() throws Exception {
        when(service.get(DATE)).thenReturn(new ThemeReplayMetricsResponse(DATE, true, true,
                ThemeReplayMetricsResponse.SafetyBoundary.replayAnalyticsOnlyBoundary(), List.of(item())));

        mvc.perform(get("/api/replay-metrics").param("date", DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayOnly", is(true)))
                .andExpect(jsonPath("$.analyticsOnly", is(true)))
                .andExpect(jsonPath("$.safetyBoundary.doesNotAffectFinalDecision", is(true)))
                .andExpect(jsonPath("$.safetyBoundary.metricsDoNotOverrideRiskGate", is(true)))
                .andExpect(jsonPath("$.items[0].themeTag", is("被動元件")))
                .andExpect(jsonPath("$.items[0].riskGateBypassCount", is(0)));
    }

    @Test
    void themeMetricsAndBuildExposeMetricsSummary() throws Exception {
        when(service.byTheme(DATE, "被動元件")).thenReturn(new ThemeReplayMetricsResponse(DATE, true, true,
                ThemeReplayMetricsResponse.SafetyBoundary.replayAnalyticsOnlyBoundary(), List.of(item())));
        when(service.build(DATE)).thenReturn(new ThemeReplayMetricsResponse.BuildResult(DATE, 1, true, true, true,
                ThemeReplayMetricsResponse.SafetyBoundary.replayAnalyticsOnlyBoundary(),
                Map.of("被動元件", new ThemeReplayMetricsResponse.MetricsSummary(new BigDecimal("1.0000"), new BigDecimal("0.8000"), 4, 0, 0, 0, 0)),
                List.of(item())));

        mvc.perform(get("/api/replay-metrics/themes/{themeTag}", "被動元件").param("date", DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].leaderRetentionRate", is(1.0)))
                .andExpect(jsonPath("$.items[0].peerDiscoveryHitRate", is(0.8)));

        mvc.perform(post("/api/replay-metrics/build").param("date", DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.builtCount", is(1)))
                .andExpect(jsonPath("$.noAutoPromotion", is(true)))
                .andExpect(jsonPath("$.metrics.被動元件.riskGateBypassCount", is(0)));
    }

    @Test
    void safetySummaryAggregatesViolationCounters() throws Exception {
        when(service.safetySummary(DATE)).thenReturn(new ThemeReplayMetricsResponse.SafetySummary(DATE, 1, 0, 0, 0, 0, 0, 0,
                false, ThemeReplayMetricsResponse.SafetyBoundary.replayAnalyticsOnlyBoundary(), true, true, true));

        mvc.perform(get("/api/replay-metrics/safety-summary").param("date", DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.safetyViolationDetected", is(false)))
                .andExpect(jsonPath("$.riskGateBypassCount", is(0)))
                .andExpect(jsonPath("$.noAutoPromotion", is(true)));
    }

    private ThemeReplayMetricsResponse.Item item() {
        var safety = ThemeReplayMetricsResponse.SafetyBoundary.replayAnalyticsOnlyBoundary();
        return new ThemeReplayMetricsResponse.Item(DATE, "被動元件", new BigDecimal("1.0000"), new BigDecimal("0.8000"),
                0, new BigDecimal("1.0000"), 4, 1, 0, 1, 0, 0, 0, 0, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO,
                "{}", safety);
    }
}
