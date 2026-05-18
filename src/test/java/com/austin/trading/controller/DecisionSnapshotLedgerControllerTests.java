package com.austin.trading.controller;

import com.austin.trading.dto.response.DecisionSnapshotLedgerResponse;
import com.austin.trading.service.DecisionSnapshotLedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DecisionSnapshotLedgerControllerTests {

    @Test
    void recentEndpointReturnsSnapshots() throws Exception {
        DecisionSnapshotLedgerService service = mock(DecisionSnapshotLedgerService.class);
        when(service.getRecent(20)).thenReturn(List.of(snapshot(1L, 42L)));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DecisionSnapshotLedgerController(service)).build();

        mvc.perform(get("/api/decision-snapshots/recent").param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].finalDecisionId").value(42))
                .andExpect(jsonPath("$[0].finalDecisionCode").value("ENTER"))
                .andExpect(jsonPath("$[0].selectedSymbolsJson").value("[\"2330\"]"));
    }

    @Test
    void byIdEndpointReturnsSnapshotWhenFound() throws Exception {
        DecisionSnapshotLedgerService service = mock(DecisionSnapshotLedgerService.class);
        when(service.getById(1L)).thenReturn(Optional.of(snapshot(1L, 42L)));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DecisionSnapshotLedgerController(service)).build();

        mvc.perform(get("/api/decision-snapshots/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.finalDecisionId").value(42));
    }

    @Test
    void byIdEndpointReturnsNotFoundWhenMissing() throws Exception {
        DecisionSnapshotLedgerService service = mock(DecisionSnapshotLedgerService.class);
        when(service.getById(404L)).thenReturn(Optional.empty());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DecisionSnapshotLedgerController(service)).build();

        mvc.perform(get("/api/decision-snapshots/404"))
                .andExpect(status().isNotFound());
    }

    @Test
    void byFinalDecisionEndpointReturnsSnapshots() throws Exception {
        DecisionSnapshotLedgerService service = mock(DecisionSnapshotLedgerService.class);
        when(service.getByFinalDecisionId(42L)).thenReturn(List.of(snapshot(1L, 42L), snapshot(2L, 42L)));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DecisionSnapshotLedgerController(service)).build();

        mvc.perform(get("/api/decision-snapshots/final-decision/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].finalDecisionId").value(42))
                .andExpect(jsonPath("$[1].id").value(2));
    }

    private DecisionSnapshotLedgerResponse snapshot(Long id, Long finalDecisionId) {
        return new DecisionSnapshotLedgerResponse(
                id,
                finalDecisionId,
                LocalDate.of(2026, 5, 18),
                "OPENING",
                "OPENING",
                77L,
                "FULL_AI_READY",
                "FULL_AI_READY",
                null,
                "ENTER",
                "[\"2330\"]",
                "[]",
                "[]",
                "[\"2330\"]",
                null,
                null,
                null,
                null,
                "{\"marketGrade\":\"A\"}",
                "{\"decision\":\"ENTER\"}",
                LocalDateTime.of(2026, 5, 18, 9, 30)
        );
    }
}
