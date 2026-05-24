package com.austin.trading.controller;

import com.austin.trading.dto.response.HotGroupRadarResponse;
import com.austin.trading.service.HotGroupRadarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HotGroupRadarControllerTest {
    private static final LocalDate DATE = LocalDate.of(2026, 5, 22);
    private HotGroupRadarService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(HotGroupRadarService.class);
        mvc = MockMvcBuilders.standaloneSetup(new HotGroupRadarController(service)).build();
    }

    @Test
    void buildAndRadarExposeShadowSafetyBoundary() throws Exception {
        var response = response(List.of(item("被動元件/MLCC", "2327", "國巨*", "THEME_LEADER", true)));
        when(service.buildFromDefaultFile(DATE, "POSTMARKET")).thenReturn(response);
        when(service.radar(DATE, "POSTMARKET")).thenReturn(response);

        mvc.perform(post("/api/hot-groups/build").param("date", DATE.toString()).param("phase", "POSTMARKET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shadowOnly", is(true)))
                .andExpect(jsonPath("$.observabilityOnly", is(true)))
                .andExpect(jsonPath("$.safetyBoundary.doesNotAffectFinalDecision", is(true)))
                .andExpect(jsonPath("$.safetyBoundary.noDirectBuy", is(true)))
                .andExpect(jsonPath("$.doesNotWriteCandidateStock", is(true)));

        mvc.perform(get("/api/hot-groups/radar").param("date", DATE.toString()).param("phase", "POSTMARKET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.themes[0].themeTag", is("被動元件/MLCC")));
    }

    @Test
    void byThemeUsesQueryParamSoSlashInThemeTagWorks() throws Exception {
        when(service.byTheme(DATE, "被動元件/MLCC")).thenReturn(response(List.of(item("被動元件/MLCC", "2492", "華新科", "SECOND_LEADER", true))));

        mvc.perform(get("/api/hot-groups/by-theme").param("date", DATE.toString()).param("themeTag", "被動元件/MLCC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.themes[0].themeTag", is("被動元件/MLCC")))
                .andExpect(jsonPath("$.signals[0].symbol", is("2492")));
    }

    @Test
    void explainMissAndCandidateFeedAreReadOnly() throws Exception {
        when(service.explainMiss(DATE, "2375")).thenReturn(new HotGroupRadarResponse.ExplainMiss(
                DATE, "2375", true, true, true, true, false, true, true, true,
                List.of("limit_risk", "not_in_final_candidates_5", "radar_watch_only"), safety()));
        when(service.candidateFeed(DATE, "POSTMARKET")).thenReturn(new HotGroupRadarResponse.CandidateFeed(
                DATE, "POSTMARKET", true, true, true, safety(),
                List.of(item("被動元件/鋁電容", "2375", "凱美", "SECOND_LEADER", true)),
                List.of(), List.of(), List.of(item("被動元件/鋁電容", "2375", "凱美", "SECOND_LEADER", true))));

        mvc.perform(get("/api/hot-groups/explain-miss").param("date", DATE.toString()).param("symbol", "2375"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hotGroupRadarWatchOnly", is(true)))
                .andExpect(jsonPath("$.safetyBoundary.doesNotWriteCandidateStock", is(true)));

        mvc.perform(get("/api/hot-groups/candidate-feed").param("date", DATE.toString()).param("phase", "POSTMARKET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doesNotWriteCandidateStock", is(true)))
                .andExpect(jsonPath("$.watchOnly[0].symbol", is("2375")))
                .andExpect(jsonPath("$.rejectDueToRisk[0].symbol", is("2375")));
    }

    private static HotGroupRadarResponse response(List<HotGroupRadarResponse.SignalItem> signals) {
        return new HotGroupRadarResponse(DATE, "POSTMARKET", true, true, true, true, true,
                safety(), List.of(new HotGroupRadarResponse.ThemeItem("被動元件/MLCC", "被動元件", new BigDecimal("100"), 1, 1, 1, 1,
                new BigDecimal("9.97"), new BigDecimal("455.0"), new BigDecimal("10"), BigDecimal.ZERO, false, "HIGH")), signals);
    }

    private static HotGroupRadarResponse.SignalItem item(String theme, String symbol, String name, String role, boolean limitRisk) {
        return new HotGroupRadarResponse.SignalItem(theme, symbol, name, role, new BigDecimal("9.97"), new BigDecimal("455.0"), BigDecimal.ONE,
                limitRisk, new BigDecimal("629000"), "WATCH_ONLY", new BigDecimal("24.43"), limitRisk ? "REJECT_LIMIT_RISK" : "WATCH_ONLY", "shadow-only");
    }

    private static HotGroupRadarResponse.SafetyBoundary safety() {
        return HotGroupRadarResponse.SafetyBoundary.shadowOnlyBoundary();
    }
}
