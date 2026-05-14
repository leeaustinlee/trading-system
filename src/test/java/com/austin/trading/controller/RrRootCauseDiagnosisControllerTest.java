package com.austin.trading.controller;

import com.austin.trading.dto.response.RrRootCauseDiagnosisResponse;
import com.austin.trading.service.BacktestService;
import com.austin.trading.service.RrRootCauseDiagnosisService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

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
}
