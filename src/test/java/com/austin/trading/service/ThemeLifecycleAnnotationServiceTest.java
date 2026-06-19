package com.austin.trading.service;

import com.austin.trading.dto.response.ThemeLifecycleAnnotationResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeLifecycleAnnotationServiceTest {
    @Test
    void advisoryActionMapsStagesToLabelsOnly() {
        assertThat(ThemeLifecycleAnnotationService.advisoryAction("DEAD")).isEqualTo("AVOID_OR_EXIT_REVIEW");
        assertThat(ThemeLifecycleAnnotationService.advisoryAction("DISTRIBUTION")).isEqualTo("TIGHTEN_REVIEW");
        assertThat(ThemeLifecycleAnnotationService.advisoryAction("OVERHEATED")).isEqualTo("NO_CHASE_WAIT_PULLBACK");
        assertThat(ThemeLifecycleAnnotationService.advisoryAction("MAINSTREAM")).isEqualTo("HOLD_THESIS_OR_PRIORITY_REVIEW");
        assertThat(ThemeLifecycleAnnotationService.advisoryAction("EMERGING")).isEqualTo("WATCH_RESEARCH_ONLY");
        assertThat(ThemeLifecycleAnnotationService.advisoryAction(null)).isEqualTo("DATA_GAP_REVIEW");
        assertThat(ThemeLifecycleAnnotationService.advisoryAction("UNKNOWN")).isEqualTo("DATA_GAP_REVIEW");
    }

    @Test
    void emptyAnnotationResponseKeepsSafetyFieldsAndDataGaps() {
        var date = LocalDate.of(2026, 6, 19);

        var response = ThemeLifecycleAnnotationResponse.of(
                date, "candidates", List.of(), List.of("TABLE_MISSING:candidate_stock"));

        assertThat(response.annotationOnly()).isTrue();
        assertThat(response.advisoryOnly()).isTrue();
        assertThat(response.doesNotAffectBuySell()).isTrue();
        assertThat(response.requestedDate()).isEqualTo(date);
        assertThat(response.targetType()).isEqualTo("candidates");
        assertThat(response.items()).isEmpty();
        assertThat(response.dataGaps()).containsExactly("TABLE_MISSING:candidate_stock");
    }
}
