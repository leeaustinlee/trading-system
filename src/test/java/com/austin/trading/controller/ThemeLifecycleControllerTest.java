package com.austin.trading.controller;

import com.austin.trading.dto.response.ThemeLifecycleResponse;
import com.austin.trading.service.ThemeLifecycleEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ThemeLifecycleControllerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 22);
    private ThemeLifecycleEngine engine;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        engine = mock(ThemeLifecycleEngine.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ThemeLifecycleController(engine)).build();
    }

    @Test
    void lifecycleListEndpointExposesReplayAdvisoryOnlyBoundary() throws Exception {
        when(engine.get(DATE)).thenReturn(new ThemeLifecycleResponse(
                DATE, true, true, ThemeLifecycleResponse.SafetyBoundary.lifecycleReplayOnlyBoundary(),
                List.of(item("被動元件", "MAINSTREAM"))));

        mockMvc.perform(get("/api/themes/lifecycle").param("date", "2026-05-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayOnly").value(true))
                .andExpect(jsonPath("$.advisoryOnly").value(true))
                .andExpect(jsonPath("$.safetyBoundary.doesNotAffectFinalDecision").value(true))
                .andExpect(jsonPath("$.safetyBoundary.doesNotAffectBuySellEnter").value(true))
                .andExpect(jsonPath("$.safetyBoundary.lifecycleDoesNotOverrideRiskGate").value(true))
                .andExpect(jsonPath("$.items[0].themeTag").value("被動元件"))
                .andExpect(jsonPath("$.items[0].stage").value("MAINSTREAM"))
                .andExpect(jsonPath("$.items[0].recommendedPlaybook[0]").value("LOW_BASE_FOLLOWER"))
                .andExpect(jsonPath("$.items[0].avoidPlaybook[0]").value("CHASE_LEADER"));
    }

    @Test
    void lifecycleThemeAndBuildEndpointsRemainReplayOnly() throws Exception {
        when(engine.getTheme(DATE, "被動元件")).thenReturn(new ThemeLifecycleResponse(
                DATE, true, true, ThemeLifecycleResponse.SafetyBoundary.lifecycleReplayOnlyBoundary(),
                List.of(item("被動元件", "MAINSTREAM"))));
        when(engine.build(DATE)).thenReturn(new ThemeLifecycleResponse.BuildResult(
                DATE, 1, true, true, ThemeLifecycleResponse.SafetyBoundary.lifecycleReplayOnlyBoundary(),
                Map.of("被動元件", "MAINSTREAM"), List.of(item("被動元件", "MAINSTREAM"))));

        mockMvc.perform(get("/api/themes/lifecycle/{themeTag}", "被動元件").param("date", "2026-05-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].stage").value("MAINSTREAM"))
                .andExpect(jsonPath("$.items[0].safetyBoundary.lifecycleDoesNotOverrideRiskGate").value(true));

        mockMvc.perform(post("/api/themes/lifecycle/build").param("date", "2026-05-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.builtCount").value(1))
                .andExpect(jsonPath("$.replayOnly").value(true))
                .andExpect(jsonPath("$.advisoryOnly").value(true))
                .andExpect(jsonPath("$.safetyBoundary.doesNotWriteCandidateStock").value(true))
                .andExpect(jsonPath("$.safetyBoundary.doesNotWriteProductionScore").value(true))
                .andExpect(jsonPath("$.stages.被動元件").value("MAINSTREAM"));
    }

    private ThemeLifecycleResponse.Item item(String themeTag, String stage) {
        return new ThemeLifecycleResponse.Item(
                DATE,
                themeTag,
                stage,
                null,
                false,
                new BigDecimal("0.70"),
                1,
                6,
                new BigDecimal("0.70"),
                3,
                new BigDecimal("0.65"),
                new BigDecimal("0.45"),
                new BigDecimal("0.05"),
                new BigDecimal("0.68"),
                new BigDecimal("0.55"),
                new BigDecimal("0.70"),
                "leader is clear, breadth expanded, continuation and peer rotation are positive",
                List.of("LOW_BASE_FOLLOWER", "PULLBACK"),
                List.of("CHASE_LEADER"),
                "{\"replayOnly\":true,\"advisoryOnly\":true}",
                ThemeLifecycleResponse.SafetyBoundary.lifecycleReplayOnlyBoundary()
        );
    }
}
