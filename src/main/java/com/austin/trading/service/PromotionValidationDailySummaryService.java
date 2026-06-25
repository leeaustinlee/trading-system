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
    private static final List<String> SUMMARY_STATUSES = List.of(
            "RESEARCH_ONLY",
            "PENDING_REVIEW",
            "CANDIDATE_POOL_SHADOW",
            "WATCH_ONLY",
            "BLOCKED_BY_RISK",
            "BLOCKED_BY_GOVERNANCE",
            "NEED_MORE_EVIDENCE");

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
        List<String> summaryStatuses = summaryStatuses(effectiveStatus);

        Map<String, Map<String, Object>> bridgeByStatus = new LinkedHashMap<>();
        for (String summaryStatus : summaryStatuses) {
            bridgeByStatus.put(summaryStatus, promotionReviewService.bridgeForwardTracking(date, date, summaryStatus));
        }
        Map<String, Object> backfill = returnBackfillService.backfillReturns(effectiveBackfillDays);

        Map<String, Object> byStatus = new LinkedHashMap<>();
        int totalQueueItems = 0;
        int totalEvidenceReady = 0;
        int totalDataGaps = 0;
        int totalRiskBlocked = 0;
        int totalGovernanceBlocked = 0;
        PromotionValidationReportResponse selectedValidation = null;

        for (String summaryStatus : summaryStatuses) {
            PromotionValidationReportResponse validation = promotionReviewService.validationReport(date, date, summaryStatus);
            if (summaryStatus.equals(effectiveStatus)) {
                selectedValidation = validation;
            }
            PromotionValidationReportResponse.Summary summary = validation.summary();
            Map<String, Object> statusOut = new LinkedHashMap<>();
            statusOut.put("status", summaryStatus);
            statusOut.put("bridge", bridgeByStatus.get(summaryStatus));
            statusOut.put("validationSummary", summary);
            statusOut.put("itemCount", summary.itemCount());
            statusOut.put("queueItems", summary.itemCount());
            statusOut.put("evidenceReadyCount", summary.evidenceReadyCount());
            statusOut.put("dataGapCount", summary.dataGapCount());
            statusOut.put("riskBlockedCount", summary.riskBlockedCount());
            statusOut.put("governanceBlockedCount", summary.governanceBlockedCount());
            statusOut.put("overallStatus", summary.overallStatus());
            statusOut.put("overallReason", summary.overallReason());
            byStatus.put(summaryStatus, statusOut);

            totalQueueItems += summary.itemCount();
            totalEvidenceReady += summary.evidenceReadyCount();
            totalDataGaps += summary.dataGapCount();
            totalRiskBlocked += summary.riskBlockedCount();
            totalGovernanceBlocked += summary.governanceBlockedCount();
        }
        if (selectedValidation == null) {
            selectedValidation = promotionReviewService.validationReport(date, date, effectiveStatus);
        }

        Map<String, Object> allStatusSummary = new LinkedHashMap<>();
        allStatusSummary.put("statuses", summaryStatuses);
        allStatusSummary.put("totalQueueItems", totalQueueItems);
        allStatusSummary.put("totalEvidenceReady", totalEvidenceReady);
        allStatusSummary.put("totalDataGaps", totalDataGaps);
        allStatusSummary.put("totalRiskBlocked", totalRiskBlocked);
        allStatusSummary.put("totalGovernanceBlocked", totalGovernanceBlocked);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dailyValidationSummaryOnly", true);
        putSafetyFlags(out);
        out.put("date", date);
        out.put("status", effectiveStatus);
        out.put("backfillDays", effectiveBackfillDays);
        out.put("bridge", bridgeByStatus.get(effectiveStatus));
        out.put("bridgeByStatus", bridgeByStatus);
        out.put("returnBackfill", backfill);
        out.put("validationSummary", selectedValidation.summary());
        out.put("graduationCriteria", selectedValidation.graduationCriteria());
        out.put("itemCount", selectedValidation.summary().itemCount());
        out.put("totalQueueItems", totalQueueItems);
        out.put("allStatusSummary", allStatusSummary);
        out.put("byStatus", byStatus);
        out.put("overallStatus", selectedValidation.summary().overallStatus());
        out.put("overallReason", selectedValidation.summary().overallReason());
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
        int totalQueueItems = 0;
        int totalEvidenceReady = 0;
        int totalDataGaps = 0;
        Map<String, Map<String, Object>> totalByStatus = emptyTotalsByStatus(summaryStatuses(effectiveStatus));
        String latestOverallStatus = null;
        String latestOverallReason = null;

        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            Map<String, Object> day = run(cursor, effectiveStatus, effectiveBackfillDays);
            dailyResults.add(day);
            totalDays++;
            int itemCount = number(day.get("itemCount"));
            totalItems += itemCount;
            totalQueueItems += number(day.get("totalQueueItems"));
            if (itemCount > 0) daysWithItems++;
            Object validationSummary = day.get("validationSummary");
            if (validationSummary instanceof PromotionValidationReportResponse.Summary summary) {
                totalEvidenceReady += summary.evidenceReadyCount();
                totalDataGaps += summary.dataGapCount();
            }
            Object byStatus = day.get("byStatus");
            if (byStatus instanceof Map<?, ?> byStatusMap) {
                mergeTotalsByStatus(totalByStatus, byStatusMap);
            }
            latestOverallStatus = String.valueOf(day.get("overallStatus"));
            latestOverallReason = String.valueOf(day.get("overallReason"));
            cursor = cursor.plusDays(1);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dailyValidationSummaryBackfillOnly", true);
        putSafetyFlags(out);
        out.put("startDate", startDate);
        out.put("endDate", endDate);
        out.put("status", effectiveStatus);
        out.put("backfillDays", effectiveBackfillDays);
        out.put("totalDays", totalDays);
        out.put("daysWithItems", daysWithItems);
        out.put("totalItems", totalItems);
        out.put("totalQueueItems", totalQueueItems);
        out.put("totalByStatus", totalByStatus);
        out.put("totalEvidenceReady", totalEvidenceReady);
        out.put("totalDataGaps", totalDataGaps);
        out.put("latestOverallStatus", latestOverallStatus);
        out.put("latestOverallReason", latestOverallReason);
        out.put("dailyResults", dailyResults);
        return out;
    }

    private List<String> summaryStatuses(String effectiveStatus) {
        List<String> statuses = new ArrayList<>(SUMMARY_STATUSES);
        if (effectiveStatus != null && !effectiveStatus.isBlank() && !statuses.contains(effectiveStatus)) {
            statuses.add(effectiveStatus);
        }
        return statuses;
    }

    private void putSafetyFlags(Map<String, Object> out) {
        out.put("reportOnly", true);
        out.put("doesNotAffectFinalDecision", true);
        out.put("doesNotAffectBuySellEnter", true);
        out.put("doesNotWriteCandidateStock", true);
        out.put("doesNotWriteProductionScore", true);
        out.put("noAutoPromotion", true);
        out.put("softBoostShadowOnly", true);
    }

    private Map<String, Map<String, Object>> emptyTotalsByStatus(List<String> statuses) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (String status : statuses) {
            Map<String, Object> totals = new LinkedHashMap<>();
            totals.put("status", status);
            totals.put("queueItems", 0);
            totals.put("itemCount", 0);
            totals.put("evidenceReadyCount", 0);
            totals.put("dataGapCount", 0);
            totals.put("riskBlockedCount", 0);
            totals.put("governanceBlockedCount", 0);
            out.put(status, totals);
        }
        return out;
    }

    private void mergeTotalsByStatus(Map<String, Map<String, Object>> totalsByStatus, Map<?, ?> byStatusMap) {
        for (Map.Entry<?, ?> entry : byStatusMap.entrySet()) {
            String status = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> statusMap)) continue;
            Map<String, Object> totals = totalsByStatus.computeIfAbsent(status, key -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("status", key);
                created.put("queueItems", 0);
                created.put("itemCount", 0);
                created.put("evidenceReadyCount", 0);
                created.put("dataGapCount", 0);
                created.put("riskBlockedCount", 0);
                created.put("governanceBlockedCount", 0);
                return created;
            });
            add(totals, "queueItems", statusMap.get("queueItems"));
            add(totals, "itemCount", statusMap.get("itemCount"));
            add(totals, "evidenceReadyCount", statusMap.get("evidenceReadyCount"));
            add(totals, "dataGapCount", statusMap.get("dataGapCount"));
            add(totals, "riskBlockedCount", statusMap.get("riskBlockedCount"));
            add(totals, "governanceBlockedCount", statusMap.get("governanceBlockedCount"));
        }
    }

    private void add(Map<String, Object> totals, String key, Object value) {
        totals.put(key, number(totals.get(key)) + number(value));
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
