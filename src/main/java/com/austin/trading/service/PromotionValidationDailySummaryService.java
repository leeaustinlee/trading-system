package com.austin.trading.service;

import com.austin.trading.dto.response.PromotionValidationReportResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PromotionValidationDailySummaryService {
    private static final String DEFAULT_STATUS = "CANDIDATE_POOL_SHADOW";
    private static final int DEFAULT_BACKFILL_DAYS = 14;

    private final PromotionReviewService promotionReviewService;
    private final CandidateForwardReturnBackfillService returnBackfillService;

    public PromotionValidationDailySummaryService(PromotionReviewService promotionReviewService,
                                                  CandidateForwardReturnBackfillService returnBackfillService) {
        this.promotionReviewService = promotionReviewService;
        this.returnBackfillService = returnBackfillService;
    }

    public Map<String, Object> run(LocalDate date) {
        return run(date, DEFAULT_STATUS, DEFAULT_BACKFILL_DAYS);
    }

    public Map<String, Object> run(LocalDate date, String status, int backfillDays) {
        String effectiveStatus = (status == null || status.isBlank()) ? DEFAULT_STATUS : status.trim().toUpperCase();
        int effectiveBackfillDays = backfillDays > 0 ? backfillDays : DEFAULT_BACKFILL_DAYS;

        Map<String, Object> bridge = promotionReviewService.bridgeForwardTracking(date, date, effectiveStatus);
        Map<String, Object> backfill = returnBackfillService.backfillReturns(effectiveBackfillDays);
        PromotionValidationReportResponse validation = promotionReviewService.validationReport(date, date, effectiveStatus);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dailyValidationSummaryOnly", true);
        out.put("reportOnly", true);
        out.put("doesNotAffectFinalDecision", true);
        out.put("doesNotAffectBuySellEnter", true);
        out.put("doesNotWriteCandidateStock", true);
        out.put("doesNotWriteProductionScore", true);
        out.put("noAutoPromotion", true);
        out.put("softBoostShadowOnly", true);
        out.put("date", date);
        out.put("status", effectiveStatus);
        out.put("backfillDays", effectiveBackfillDays);
        out.put("bridge", bridge);
        out.put("returnBackfill", backfill);
        out.put("validationSummary", validation.summary());
        out.put("graduationCriteria", validation.graduationCriteria());
        out.put("itemCount", validation.summary().itemCount());
        out.put("overallStatus", validation.summary().overallStatus());
        out.put("overallReason", validation.summary().overallReason());
        return out;
    }
}
