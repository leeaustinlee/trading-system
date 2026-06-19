package com.austin.trading.service;

import com.austin.trading.dto.response.LifecycleExitReviewResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleExitReviewServiceTest {
    @Test
    void reviewMappingMatchesP3CSpecAndNeverEnablesExecution() {
        assertThat(LifecycleExitReviewService.reviewMapping("DISTRIBUTION", null))
                .isEqualTo(new LifecycleExitReviewService.ReviewMapping("TIGHTEN_STOP_REVIEW", "HIGH"));
        assertThat(LifecycleExitReviewService.reviewMapping("DEAD", null))
                .isEqualTo(new LifecycleExitReviewService.ReviewMapping("HIGH_PRIORITY_EXIT_REVIEW", "CRITICAL"));
        assertThat(LifecycleExitReviewService.reviewMapping("OVERHEATED", null))
                .isEqualTo(new LifecycleExitReviewService.ReviewMapping("NO_ADD_NO_CHASE", "MEDIUM"));
        assertThat(LifecycleExitReviewService.reviewMapping("MAINSTREAM", null))
                .isEqualTo(new LifecycleExitReviewService.ReviewMapping("HOLD_THESIS", "LOW"));
        assertThat(LifecycleExitReviewService.reviewMapping("EMERGING", null))
                .isEqualTo(new LifecycleExitReviewService.ReviewMapping("WATCH_RESEARCH_ONLY", "LOW"));
        assertThat(LifecycleExitReviewService.reviewMapping(null, null))
                .isEqualTo(new LifecycleExitReviewService.ReviewMapping("DATA_GAP_REVIEW", "REVIEW"));
        assertThat(LifecycleExitReviewService.reviewMapping("DEAD", "LIFECYCLE_STATE_MISSING"))
                .isEqualTo(new LifecycleExitReviewService.ReviewMapping("DATA_GAP_REVIEW", "REVIEW"));
    }

    @Test
    void responseFactoryKeepsTopLevelSafetyDefaultsFalseForMutation() {
        LocalDate date = LocalDate.of(2026, 6, 19);

        LifecycleExitReviewResponse response = LifecycleExitReviewResponse.of(
                date, 0, List.of(), List.of("TABLE_MISSING:position"));

        assertThat(response.reviewOnly()).isTrue();
        assertThat(response.advisoryOnly()).isTrue();
        assertThat(response.doesNotAffectBuySell()).isTrue();
        assertThat(response.autoSellEnabled()).isFalse();
        assertThat(response.stopMutationEnabled()).isFalse();
        assertThat(response.positionMutationEnabled()).isFalse();
        assertThat(response.requestedDate()).isEqualTo(date);
        assertThat(response.rows()).isEmpty();
        assertThat(response.items()).isEmpty();
        assertThat(response.dataGaps()).containsExactly("TABLE_MISSING:position");
    }
}
