package com.austin.trading.controller;

import com.austin.trading.dto.request.KolSignalCreateRequest;
import com.austin.trading.dto.response.KolSignalResponse;
import com.austin.trading.dto.response.KolShadowReportResponse;
import com.austin.trading.dto.response.NarrativeDashboardResponse;
import com.austin.trading.service.KolSignalIngestionService;
import com.austin.trading.service.KolSignalShadowModeService;
import com.austin.trading.service.NarrativeDashboardService;
import com.austin.trading.service.NarrativeShadowReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NarrativeControllerTests {

    @Test
    void submitTranscriptDelegatesToWeakSignalIngestion() {
        KolSignalIngestionService ingestion = mock(KolSignalIngestionService.class);
        NarrativeDashboardService dashboard = mock(NarrativeDashboardService.class);
        KolSignalShadowModeService shadow = mock(KolSignalShadowModeService.class);
        NarrativeShadowReviewService review = mock(NarrativeShadowReviewService.class);
        NarrativeController controller = new NarrativeController(ingestion, dashboard, shadow, review);
        LocalDate date = LocalDate.of(2026, 5, 21);
        KolSignalCreateRequest request = new KolSignalCreateRequest(
                date, "gooaye", "PODCAST", "股癌 transcript", "被動元件 transcript", Map.of());
        KolSignalResponse response = new KolSignalResponse(10L, date, "gooaye", "PODCAST", "股癌 transcript",
                "RAW", "abc", false, false, LocalDateTime.of(2026, 5, 21, 15, 0));
        when(ingestion.create(request)).thenReturn(response);

        ResponseEntity<?> result = controller.submitTranscript(request);

        assertThat(result.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(result.getBody()).isEqualTo(response);
    }

    @Test
    void dashboardReturnsNarrativeRows() {
        KolSignalIngestionService ingestion = mock(KolSignalIngestionService.class);
        NarrativeDashboardService dashboard = mock(NarrativeDashboardService.class);
        KolSignalShadowModeService shadow = mock(KolSignalShadowModeService.class);
        NarrativeShadowReviewService review = mock(NarrativeShadowReviewService.class);
        NarrativeController controller = new NarrativeController(ingestion, dashboard, shadow, review);
        LocalDate date = LocalDate.of(2026, 5, 21);
        var expected = new NarrativeDashboardResponse(date, true, "weak", List.of(),
                Map.of(), List.of(), List.of(), List.of(), 0L);
        when(dashboard.dashboard(date)).thenReturn(expected);

        assertThat(controller.dashboard(date)).isEqualTo(expected);
    }

    @Test
    void shadowEndpointsExposeOnDemandNarrativeReorderReportOnly() {
        KolSignalIngestionService ingestion = mock(KolSignalIngestionService.class);
        NarrativeDashboardService dashboard = mock(NarrativeDashboardService.class);
        KolSignalShadowModeService shadow = mock(KolSignalShadowModeService.class);
        NarrativeShadowReviewService review = mock(NarrativeShadowReviewService.class);
        NarrativeController controller = new NarrativeController(ingestion, dashboard, shadow, review);
        LocalDate date = LocalDate.of(2026, 5, 21);
        var report = new KolShadowReportResponse(date, 1, List.of(
                new KolShadowReportResponse.Item("2382", "廣達", "AI伺服器",
                        new BigDecimal("8.0000"), new BigDecimal("0.2000"), new BigDecimal("8.2000"),
                        "LOW", null, "shadow only; production candidate score and final decision are unchanged")
        ), "computedOnDemand=true; not persisted; shadow only; production decision unchanged");
        when(shadow.run(date)).thenReturn(report);
        when(shadow.report(date)).thenReturn(report);

        assertThat(controller.shadowRun(date)).isEqualTo(report);
        assertThat(controller.shadowReport(date).note())
                .contains("shadow only")
                .contains("production decision unchanged");
    }
}
