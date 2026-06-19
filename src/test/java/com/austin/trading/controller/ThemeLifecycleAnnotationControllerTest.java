package com.austin.trading.controller;

import com.austin.trading.dto.response.ThemeLifecycleAnnotationResponse;
import com.austin.trading.service.ThemeLifecycleAnnotationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ThemeLifecycleAnnotationControllerTest {
    private static final LocalDate DATE = LocalDate.of(2026, 6, 19);

    private ThemeLifecycleAnnotationService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = new FakeThemeLifecycleAnnotationService();
        mockMvc = MockMvcBuilders.standaloneSetup(new ThemeLifecycleAnnotationController(service)).build();
    }

    @Test
    void candidatesRouteExposesSafetyFieldsAndItems() throws Exception {
        ((FakeThemeLifecycleAnnotationService) service).candidatesResponse = response("candidates");

        mockMvc.perform(get("/api/theme-lifecycle/annotations/candidates").param("date", "2026-06-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annotationOnly").value(true))
                .andExpect(jsonPath("$.advisoryOnly").value(true))
                .andExpect(jsonPath("$.doesNotAffectBuySell").value(true))
                .andExpect(jsonPath("$.requestedDate[0]").value(2026))
                .andExpect(jsonPath("$.requestedDate[1]").value(6))
                .andExpect(jsonPath("$.requestedDate[2]").value(19))
                .andExpect(jsonPath("$.targetType").value("candidates"))
                .andExpect(jsonPath("$.items[0].symbol").value("2327"))
                .andExpect(jsonPath("$.items[0].stage").value("OVERHEATED"))
                .andExpect(jsonPath("$.items[0].advisoryAction").value("NO_CHASE_WAIT_PULLBACK"))
                .andExpect(jsonPath("$.items[0].annotationOnly").value(true))
                .andExpect(jsonPath("$.items[0].doesNotAffectBuySell").value(true));
    }

    @Test
    void allAnnotationRoutesAreWired() throws Exception {
        FakeThemeLifecycleAnnotationService fake = (FakeThemeLifecycleAnnotationService) service;
        fake.watchlistResponse = response("watchlist");
        fake.rankingResponse = response("ranking");
        fake.positionsResponse = response("positions");

        mockMvc.perform(get("/api/theme-lifecycle/annotations/watchlist").param("date", "2026-06-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetType").value("watchlist"));
        mockMvc.perform(get("/api/theme-lifecycle/annotations/ranking").param("date", "2026-06-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetType").value("ranking"));
        mockMvc.perform(get("/api/theme-lifecycle/annotations/positions").param("date", "2026-06-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetType").value("positions"));
    }

    private ThemeLifecycleAnnotationResponse response(String targetType) {
        return ThemeLifecycleAnnotationResponse.of(DATE, targetType, List.of(new ThemeLifecycleAnnotationResponse.Item(
                "2327", "國巨", "MLCC", DATE, DATE, "CANDIDATE", "LEADER", null,
                null, null, null, "OVERHEATED", "EMERGING", true, new BigDecimal("0.8200"),
                new BigDecimal("0.8100"), 11, 2, 5, new BigDecimal("0.3200"),
                new BigDecimal("0.8300"), "[\"WAIT_PULLBACK\"]", "[\"CHASE_LEADER\"]",
                "NO_CHASE_WAIT_PULLBACK", true, true, null)), List.of());
    }

    private static class FakeThemeLifecycleAnnotationService extends ThemeLifecycleAnnotationService {
        private ThemeLifecycleAnnotationResponse candidatesResponse;
        private ThemeLifecycleAnnotationResponse watchlistResponse;
        private ThemeLifecycleAnnotationResponse rankingResponse;
        private ThemeLifecycleAnnotationResponse positionsResponse;

        @Override
        public ThemeLifecycleAnnotationResponse candidates(LocalDate date) {
            return candidatesResponse;
        }

        @Override
        public ThemeLifecycleAnnotationResponse watchlist(LocalDate date) {
            return watchlistResponse;
        }

        @Override
        public ThemeLifecycleAnnotationResponse ranking(LocalDate date) {
            return rankingResponse;
        }

        @Override
        public ThemeLifecycleAnnotationResponse positions(LocalDate date) {
            return positionsResponse;
        }
    }
}
