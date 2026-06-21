package com.austin.trading.service;

import com.austin.trading.dto.response.PromotionValidationReportResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PromotionValidationDailySummaryServiceTest {
    private static final LocalDate DATE = LocalDate.of(2026, 6, 19);

    @Test
    void runBuildsBridgeBackfillsReturnsAndProducesReportOnlySummary() {
        PromotionReviewService reviewService = mock(PromotionReviewService.class);
        CandidateForwardReturnBackfillService backfillService = mock(CandidateForwardReturnBackfillService.class);
        when(reviewService.bridgeForwardTracking(DATE, DATE, "CANDIDATE_POOL_SHADOW"))
                .thenReturn(Map.of("written", 1, "trackingBridgeOnly", true));
        when(backfillService.backfillReturns(14)).thenReturn(Map.of("processedRows", 1, "dataGapRows", 1));
        var validation = PromotionValidationReportResponse.of(DATE, DATE, "CANDIDATE_POOL_SHADOW",
                new PromotionValidationReportResponse.GraduationCriteria(10, new BigDecimal("0.55"), BigDecimal.ZERO,
                        new BigDecimal("0.25"), new BigDecimal("-8")),
                new PromotionValidationReportResponse.Summary(1, 0, 1, 0, 0,
                        null, null, null, null, "BLOCKED_BY_DATA_GAP", "insufficient completed forward-return evidence or missing market data"),
                java.util.List.of());
        when(reviewService.validationReport(DATE, DATE, "CANDIDATE_POOL_SHADOW")).thenReturn(validation);

        PromotionValidationDailySummaryService service = new PromotionValidationDailySummaryService(reviewService, backfillService);
        Map<String, Object> result = service.run(DATE);

        assertThat(result).containsEntry("dailyValidationSummaryOnly", true)
                .containsEntry("reportOnly", true)
                .containsEntry("doesNotAffectFinalDecision", true)
                .containsEntry("doesNotAffectBuySellEnter", true)
                .containsEntry("doesNotWriteCandidateStock", true)
                .containsEntry("doesNotWriteProductionScore", true)
                .containsEntry("noAutoPromotion", true)
                .containsEntry("softBoostShadowOnly", true)
                .containsEntry("overallStatus", "BLOCKED_BY_DATA_GAP")
                .containsEntry("itemCount", 1);
        verify(reviewService).bridgeForwardTracking(DATE, DATE, "CANDIDATE_POOL_SHADOW");
        verify(backfillService).backfillReturns(14);
        verify(reviewService).validationReport(DATE, DATE, "CANDIDATE_POOL_SHADOW");
    }
}
