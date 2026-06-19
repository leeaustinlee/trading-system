package com.austin.trading.controller;

import com.austin.trading.dto.response.LifecycleExitReviewResponse;
import com.austin.trading.service.LifecycleExitReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LifecycleExitReviewControllerTest {
    private static final LocalDate DATE = LocalDate.of(2026, 6, 19);

    private FakeLifecycleExitReviewService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = new FakeLifecycleExitReviewService();
        mockMvc = MockMvcBuilders.standaloneSetup(new LifecycleExitReviewController(service)).build();
    }

    @Test
    void getReportExposesSafetyFieldsAndRows() throws Exception {
        service.reportResponse = response(0);

        mockMvc.perform(get("/api/theme-lifecycle/exit-review").param("date", "2026-06-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewOnly").value(true))
                .andExpect(jsonPath("$.advisoryOnly").value(true))
                .andExpect(jsonPath("$.doesNotAffectBuySell").value(true))
                .andExpect(jsonPath("$.autoSellEnabled").value(false))
                .andExpect(jsonPath("$.stopMutationEnabled").value(false))
                .andExpect(jsonPath("$.positionMutationEnabled").value(false))
                .andExpect(jsonPath("$.requestedDate[0]").value(2026))
                .andExpect(jsonPath("$.rows[0].symbol").value("2327"))
                .andExpect(jsonPath("$.items[0].reviewAction").value("HIGH_PRIORITY_EXIT_REVIEW"))
                .andExpect(jsonPath("$.items[0].reviewOnly").value(true))
                .andExpect(jsonPath("$.items[0].autoSellEnabled").value(false));
    }

    @Test
    void rebuildIsExplicitPostRoute() throws Exception {
        service.rebuildResponse = response(1);

        mockMvc.perform(post("/api/theme-lifecycle/exit-review/rebuild").param("date", "2026-06-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rebuiltCount").value(1))
                .andExpect(jsonPath("$.reviewOnly").value(true))
                .andExpect(jsonPath("$.doesNotAffectBuySell").value(true));
    }

    private LifecycleExitReviewResponse response(int rebuiltCount) {
        return LifecycleExitReviewResponse.of(DATE, rebuiltCount, List.of(new LifecycleExitReviewResponse.Item(
                1L, DATE, "2327", "Yageo", 10L, "MLCC", "DEAD", "DISTRIBUTION",
                new BigDecimal("0.1200"), 2, 4, 1, new BigDecimal("-0.5000"),
                new BigDecimal("0.3000"), "HIGH_PRIORITY_EXIT_REVIEW", "CRITICAL",
                true, false, false, false, "OPEN", "EXIT_REVIEW", "BROKEN",
                "WEAK", null, "{\"reviewOnly\":true}", null)), List.of());
    }

    private static class FakeLifecycleExitReviewService extends LifecycleExitReviewService {
        private LifecycleExitReviewResponse reportResponse;
        private LifecycleExitReviewResponse rebuildResponse;

        @Override
        public LifecycleExitReviewResponse report(LocalDate date) {
            return reportResponse;
        }

        @Override
        public LifecycleExitReviewResponse rebuild(LocalDate date) {
            return rebuildResponse;
        }
    }
}
