package com.austin.trading.controller;

import com.austin.trading.service.ThemeFirstDashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ThemeFirstDashboardControllerTest {

    @Test
    void dashboardEndpointReturnsReadOnlyHtml() throws Exception {
        ThemeFirstDashboardService service = mock(ThemeFirstDashboardService.class);
        LocalDate date = LocalDate.of(2026, 5, 25);
        when(service.renderHtml(date)).thenReturn("""
                <!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head>
                <body><main class=\"theme-first-dashboard mobile-card responsive-grid\">Theme-first Ops Dashboard</main></body></html>
                """);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ThemeFirstDashboardController(service)).build();

        mvc.perform(get("/dashboard/theme-first").param("date", "2026-05-25"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("Theme-first Ops Dashboard")))
                .andExpect(content().string(containsString("viewport")))
                .andExpect(content().string(containsString("mobile-card")));

        verify(service).renderHtml(date);
    }

    @Test
    void dashboardApiIsReadOnlyAndReportsNoProductionWrites() throws Exception {
        ThemeFirstDashboardService service = mock(ThemeFirstDashboardService.class);
        LocalDate date = LocalDate.of(2026, 5, 25);
        when(service.readOnlyMetadata(date)).thenReturn(Map.of(
                "tradingDate", "2026-05-25",
                "readOnly", true,
                "doesNotWriteCandidateStock", true,
                "doesNotWriteFinalDecision", true,
                "noAutoPromotion", true));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ThemeFirstDashboardController(service)).build();

        mvc.perform(get("/api/dashboard/theme-first").param("date", "2026-05-25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.doesNotWriteCandidateStock").value(true))
                .andExpect(jsonPath("$.doesNotWriteFinalDecision").value(true))
                .andExpect(jsonPath("$.noAutoPromotion").value(true));

        verify(service).readOnlyMetadata(date);
    }
}
