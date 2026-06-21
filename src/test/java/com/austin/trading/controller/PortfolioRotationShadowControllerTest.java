package com.austin.trading.controller;

import com.austin.trading.dto.response.PortfolioRotationShadowResponse;
import com.austin.trading.service.PortfolioRotationShadowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PortfolioRotationShadowControllerTest {
    private PortfolioRotationShadowService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(PortfolioRotationShadowService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PortfolioDecisionController(null, null, service)).build();
    }

    @Test
    void endpointReturnsRotationShadowSafetyFlagsAndRows() throws Exception {
        LocalDate end = LocalDate.of(2026, 6, 20);
        var item = new PortfolioRotationShadowResponse.Item(
                end, "9999", "NewCo", "AI", new BigDecimal("88"),
                "1111", "WeakHold", "OLD", new BigDecimal("70"),
                new BigDecimal("0.60"), null, true, "SHADOW_ROTATE",
                "SHADOW_ONLY advisoryOnly=true doesNotAffectBuySell=true doesNotMutatePositions=true doesNotAffectRiskGate=true");
        var response = PortfolioRotationShadowResponse.of(60, end.minusDays(59), end, 2, 3, List.of(item),
                List.of("OPPORTUNITY_DELTA_DATA_GAP:candidate_forward_tracking"));
        when(service.report(60)).thenReturn(response);

        mvc.perform(get("/api/portfolio/rotation-shadow").param("days", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shadowOnly", is(true)))
                .andExpect(jsonPath("$.advisoryOnly", is(true)))
                .andExpect(jsonPath("$.doesNotAffectBuySell", is(true)))
                .andExpect(jsonPath("$.doesNotMutatePositions", is(true)))
                .andExpect(jsonPath("$.doesNotAffectRiskGate", is(true)))
                .andExpect(jsonPath("$.requestedDays", is(60)))
                .andExpect(jsonPath("$.items[0].candidateSymbol", is("9999")))
                .andExpect(jsonPath("$.items[0].weakestHoldingSymbol", is("1111")))
                .andExpect(jsonPath("$.items[0].shadowAction", is("SHADOW_ROTATE")))
                .andExpect(jsonPath("$.items[0].opportunityDeltaDataGapped", is(true)));

        assertThat(response.safetyNote()).contains("no position mutation", "no portfolio risk-gate change");
    }
}
