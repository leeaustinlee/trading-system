package com.austin.trading.controller;

import com.austin.trading.dto.response.ThemeReplaySummaryResponse;
import com.austin.trading.dto.response.ThemeReplayTimelineResponse;
import com.austin.trading.service.ThemeReplayTimelineService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ThemeReplayControllerTest {

    @Test
    void timelineEndpointExposesReplayOnlySafetyBoundary() throws Exception {
        ThemeReplayTimelineService service = mock(ThemeReplayTimelineService.class);
        LocalDate date = LocalDate.of(2026, 5, 22);
        var boundary = ThemeReplayTimelineResponse.SafetyBoundary.replayOnlyBoundary();
        var snapshot = new ThemeReplaySummaryResponse(
                date, "被動元件", "MAINSTREAM", "2327", 1, 5, 6,
                0, 0, 1, 6, 0, null,
                new java.math.BigDecimal("0.70"), "leader is clear", List.of("LOW_BASE_FOLLOWER", "PULLBACK"), List.of("CHASE_LEADER"),
                true, true, boundary
        );
        var timeline = new ThemeReplayTimelineResponse(
                date, "被動元件", "2327", snapshot,
                List.of(new ThemeReplayTimelineResponse.Node(
                        "2327", "國巨", "THEME_LEADER", "THEME_LEADER", true, true,
                        "2327", true, false, false, null, null, null, null, null,
                        true, "leaderTradable=false", "replay-only", "governance", null
                )),
                List.of(),
                List.of(new ThemeReplayTimelineResponse.Event("RISK_REJECTED", "2327", "risk gate blocked", null)),
                "MAINSTREAM", new java.math.BigDecimal("0.70"), "leader is clear", List.of("LOW_BASE_FOLLOWER", "PULLBACK"), List.of("CHASE_LEADER"),
                boundary,
                true,
                true
        );
        when(service.timeline(date, "被動元件")).thenReturn(timeline);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ThemeReplayController(service)).build();

        mvc.perform(get("/api/theme-replay/themes/{themeTag}/timeline", "被動元件").param("date", "2026-05-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shadowOnly").value(true))
                .andExpect(jsonPath("$.replayOnly").value(true))
                .andExpect(jsonPath("$.safetyBoundary.doesNotAffectFinalDecision").value(true))
                .andExpect(jsonPath("$.safetyBoundary.doesNotAffectBuySellEnter").value(true))
                .andExpect(jsonPath("$.safetyBoundary.researchUniverseNotTradable").value(true))
                .andExpect(jsonPath("$.nodes[0].leadershipOnly").value(true))
                .andExpect(jsonPath("$.nodes[0].tradableUniverse").value(false))
                .andExpect(jsonPath("$.events[0].eventType").value("RISK_REJECTED"));
    }
}
