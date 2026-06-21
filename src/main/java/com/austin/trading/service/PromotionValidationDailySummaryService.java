package com.austin.trading.service;

import com.austin.trading.dto.response.PromotionValidationReportResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    public Map<String, Object> backfill(LocalDate startDate, LocalDate endDate, String status, int backfillDays) {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate are required");
        }
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must be >= startDate");
        }
        String effectiveStatus = (status == null || status.isBlank()) ? DEFAULT_STATUS : status.trim().toUpperCase();
        int effectiveBackfillDays = backfillDays > 0 ? backfillDays : DEFAULT_BACKFILL_DAYS;

        List<Map<String, Object>> dailyResults = new ArrayList<>();
        int totalDays = 0;
        int daysWithItems = 0;
        int totalItems = 0;
        int totalEvidenceReady = 0;
        int totalDataGaps = 0;
        String latestOverallStatus = null;
        String latestOverallReason = null;

        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            Map<String, Object> day = run(cursor, effectiveStatus, effectiveBackfillDays);
            dailyResults.add(day);
            totalDays++;
            int itemCount = number(day.get("itemCount"));
            totalItems += itemCount;
            if (itemCount > 0) daysWithItems++;
            Object validationSummary = day.get("validationSummary");
            if (validationSummary instanceof PromotionValidationReportResponse.Summary summary) {
                totalEvidenceReady += summary.evidenceReadyCount();
                totalDataGaps += summary.dataGapCount();
            }
            latestOverallStatus = String.valueOf(day.get("overallStatus"));
            latestOverallReason = String.valueOf(day.get("overallReason"));
            cursor = cursor.plusDays(1);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dailyValidationSummaryBackfillOnly", true);
        out.put("reportOnly", true);
        out.put("doesNotAffectFinalDecision", true);
        out.put("doesNotAffectBuySellEnter", true);
        out.put("doesNotWriteCandidateStock", true);
        out.put("doesNotWriteProductionScore", true);
        out.put("noAutoPromotion", true);
        out.put("softBoostShadowOnly", true);
        out.put("startDate", startDate);
        out.put("endDate", endDate);
        out.put("status", effectiveStatus);
        out.put("backfillDays", effectiveBackfillDays);
        out.put("totalDays", totalDays);
        out.put("daysWithItems", daysWithItems);
        out.put("totalItems", totalItems);
        out.put("totalEvidenceReady", totalEvidenceReady);
        out.put("totalDataGaps", totalDataGaps);
        out.put("latestOverallStatus", latestOverallStatus);
        out.put("latestOverallReason", latestOverallReason);
        out.put("dailyResults", dailyResults);
        return out;
    }

    private int number(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
