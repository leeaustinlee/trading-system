package com.austin.trading.controller;

import com.austin.trading.dto.response.RrRootCauseDiagnosisResponse;
import com.austin.trading.service.BacktestService;
import com.austin.trading.service.RrRootCauseDiagnosisService;
import com.austin.trading.service.RrShadowValidationService;
import com.austin.trading.service.RrValidationCoverageRepairService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RrRootCauseDiagnosisControllerTest {

    @Test
    void rrRootCauseDiagnosisDelegatesToReadOnlyService() {
        RrRootCauseDiagnosisService diagnosisService = mock(RrRootCauseDiagnosisService.class);
        RrRootCauseDiagnosisResponse response = new RrRootCauseDiagnosisResponse(
                60, 1, 1, 1, new BigDecimal("100.00"),
                null, null, null, null,
                List.of(),
                new RrRootCauseDiagnosisResponse.ShadowImpact(
                        1, new BigDecimal("100.00"), null, null, null, null,
                        null, 0, 0, "INSUFFICIENT_SAMPLE", List.of("INSUFFICIENT_SAMPLE")),
                List.of("DATA_GAP")
        );
        when(diagnosisService.diagnose(60)).thenReturn(response);

        BacktestController controller = new BacktestController(mock(BacktestService.class), diagnosisService);

        assertThat(controller.rrRootCauseDiagnosis(60)).isSameAs(response);
        verify(diagnosisService).diagnose(60);
    }

    @Test
    void rrShadowValidationRepairCoverageDelegatesToRepairService() {
        RrRootCauseDiagnosisService diagnosisService = mock(RrRootCauseDiagnosisService.class);
        RrShadowValidationService validationService = mock(RrShadowValidationService.class);
        RrValidationCoverageRepairService repairService = mock(RrValidationCoverageRepairService.class);
        LocalDate date = LocalDate.now().minusDays(5);
        RrShadowValidationService.Summary before = summary("0.00", date);
        RrShadowValidationService.Summary after = summary("100.00", date);
        RrValidationCoverageRepairService.RepairResponse response =
                new RrValidationCoverageRepairService.RepairResponse(
                        60, date.minusDays(5), date.plusDays(20),
                        before, after, List.of("2330"),
                        Map.of("marketIndexDaily", 1, "candidateForwardTracking", 0, "rrShadowValidation", 1),
                        Map.of("T1", 0, "T3", 0, "T5", 1, "T10", 1),
                        List.of(), List.of("t00@" + date), Map.of("2330@" + date, List.of("T5", "T10")),
                        date, date, new BigDecimal("100.00"),
                        Map.of(), Map.of(), Map.of(), "SHADOW_ONLY");
        when(repairService.repairCoverage(60)).thenReturn(response);

        BacktestController controller = new BacktestController(
                mock(BacktestService.class), diagnosisService, validationService, repairService);

        assertThat(controller.rrShadowValidationRepairCoverage(60)).isSameAs(response);
        verify(repairService).repairCoverage(60);
    }

    private RrShadowValidationService.Summary summary(String coverage, LocalDate date) {
        return new RrShadowValidationService.Summary(
                60, date.minusDays(55), LocalDate.now(),
                1, 1, 0, 1, new BigDecimal("100.00"),
                null, null, null, null,
                Map.of("T1", 0, "T3", 0, "T5", 1, "T10", 1),
                0, 0, Map.of("STOP_TOO_WIDE", 1L), List.of("2330"),
                new BigDecimal(coverage),
                new RrShadowValidationService.CoverageGapDetails(
                        List.of(), List.of(), Map.of(), date, date)
        );
    }
}
