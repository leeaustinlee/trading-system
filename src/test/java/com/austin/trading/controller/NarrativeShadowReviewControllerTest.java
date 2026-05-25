package com.austin.trading.controller;

import com.austin.trading.dto.response.NarrativeShadowReviewResponse;
import com.austin.trading.service.KolSignalIngestionService;
import com.austin.trading.service.KolSignalShadowModeService;
import com.austin.trading.service.NarrativeDashboardService;
import com.austin.trading.service.NarrativeShadowReviewService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NarrativeShadowReviewControllerTest {

    @Test
    void shadowReviewEndpointReturnsPersistedOrAggregatedObservationOnlyReview() {
        KolSignalIngestionService ingestion = mock(KolSignalIngestionService.class);
        NarrativeDashboardService dashboard = mock(NarrativeDashboardService.class);
        KolSignalShadowModeService shadow = mock(KolSignalShadowModeService.class);
        NarrativeShadowReviewService review = mock(NarrativeShadowReviewService.class);
        NarrativeController controller = new NarrativeController(ingestion, dashboard, shadow, review);
        LocalDate date = LocalDate.of(2026, 5, 21);
        var expected = NarrativeShadowReviewResponse.empty(date, "test");
        when(review.report(date)).thenReturn(expected);

        assertThat(controller.shadowReview(date)).isEqualTo(expected);
        assertThat(controller.shadowReview(date).shadowOnly()).isTrue();
        assertThat(controller.shadowReview(date).productionDecisionAllowed()).isFalse();
    }
}
