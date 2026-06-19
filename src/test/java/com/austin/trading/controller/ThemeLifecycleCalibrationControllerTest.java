package com.austin.trading.controller;

import com.austin.trading.dto.response.ThemeLifecycleCalibrationResponse;
import com.austin.trading.service.ThemeLifecycleCalibrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ThemeLifecycleCalibrationControllerTest {
    private FakeThemeLifecycleCalibrationService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = new FakeThemeLifecycleCalibrationService();
        mockMvc = MockMvcBuilders.standaloneSetup(new ThemeLifecycleCalibrationController(service)).build();
    }

    @Test
    void calibrationEndpointReturnsReadOnlySafetyFields() throws Exception {
        ThemeLifecycleCalibrationResponse response = new ThemeLifecycleCalibrationResponse(
                true, true, true, 60,
                LocalDate.of(2026, 4, 21), LocalDate.of(2026, 6, 19),
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 18), 12,
                Map.of("theme_lifecycle_state", new ThemeLifecycleCalibrationResponse.DataCoverage(
                        "theme_lifecycle_state", 3, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 18), 2, true, null)),
                List.of(new ThemeLifecycleCalibrationResponse.StageDistribution(
                        "MAINSTREAM", 3, new BigDecimal("0.7000"), new BigDecimal("4.0000"),
                        new BigDecimal("8.0000"), new BigDecimal("0.3000"))),
                ThemeLifecycleCalibrationResponse.FunnelSummary.empty(),
                ThemeLifecycleCalibrationResponse.ThemeAdmissionSummary.empty(),
                List.of(),
                ThemeLifecycleCalibrationResponse.PredictivePower.insufficient(2, "INSUFFICIENT_SAMPLE"),
                List.of(new ThemeLifecycleCalibrationResponse.CalibrationFinding(
                        "PREDICTIVE_POWER_DATA_GAP", "INFO", "INSUFFICIENT_SAMPLE")),
                List.of("INSUFFICIENT_PREDICTIVE_SAMPLE:n=2"));
        service.calibrationResponse = response;

        mockMvc.perform(get("/api/theme-lifecycle/calibration").param("days", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.advisoryOnly").value(true))
                .andExpect(jsonPath("$.doesNotAffectBuySell").value(true))
                .andExpect(jsonPath("$.requestedDays").value(60))
                .andExpect(jsonPath("$.stageDistribution[0].stage").value("MAINSTREAM"))
                .andExpect(jsonPath("$.lifecycleMetricPredictivePower.dataGapReason").value("INSUFFICIENT_SAMPLE"));

        org.assertj.core.api.Assertions.assertThat(service.calibrationDays).isEqualTo(60);
    }

    @Test
    void dataGapsEndpointDelegatesToSubsetReport() throws Exception {
        Map<String, Object> response = Map.of(
                "readOnly", true,
                "advisoryOnly", true,
                "doesNotAffectBuySell", true,
                "requestedDays", 30,
                "dataGaps", List.of("NO_ROWS_IN_REQUESTED_WINDOW:ranking_topn_shadow_result"));
        service.dataGapsResponse = response;

        mockMvc.perform(get("/api/theme-lifecycle/data-gaps").param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readOnly").value(true))
                .andExpect(jsonPath("$.dataGaps[0]").value("NO_ROWS_IN_REQUESTED_WINDOW:ranking_topn_shadow_result"));

        org.assertj.core.api.Assertions.assertThat(service.dataGapsDays).isEqualTo(30);
    }

    private static class FakeThemeLifecycleCalibrationService extends ThemeLifecycleCalibrationService {
        private ThemeLifecycleCalibrationResponse calibrationResponse;
        private Map<String, Object> dataGapsResponse;
        private int calibrationDays;
        private int dataGapsDays;

        @Override
        public ThemeLifecycleCalibrationResponse calibration(int days) {
            this.calibrationDays = days;
            return calibrationResponse;
        }

        @Override
        public Map<String, Object> dataGaps(int days) {
            this.dataGapsDays = days;
            return dataGapsResponse;
        }
    }
}
