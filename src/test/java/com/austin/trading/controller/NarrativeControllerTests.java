package com.austin.trading.controller;

import com.austin.trading.dto.request.KolSignalCreateRequest;
import com.austin.trading.dto.response.KolSignalResponse;
import com.austin.trading.dto.response.NarrativeDashboardResponse;
import com.austin.trading.service.KolSignalIngestionService;
import com.austin.trading.service.NarrativeDashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

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
        NarrativeController controller = new NarrativeController(ingestion, dashboard);
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
        NarrativeController controller = new NarrativeController(ingestion, dashboard);
        LocalDate date = LocalDate.of(2026, 5, 21);
        var expected = new NarrativeDashboardResponse(date, true, "weak", List.of());
        when(dashboard.dashboard(date)).thenReturn(expected);

        assertThat(controller.dashboard(date)).isEqualTo(expected);
    }
}
