package com.austin.trading.controller;

import com.austin.trading.dto.request.PromotionReviewDecisionRequest;
import com.austin.trading.dto.response.BuildOperationResponse;
import com.austin.trading.dto.response.PromotionGraduationReadinessResponse;
import com.austin.trading.dto.response.PromotionPolicySimulationResponse;
import com.austin.trading.dto.response.PromotionReviewResponse;
import com.austin.trading.dto.response.PromotionValidationReportResponse;
import com.austin.trading.service.BuildOperationsService;
import com.austin.trading.service.PromotionReviewService;
import com.austin.trading.service.PromotionValidationDailySummaryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/promotion-review")
public class PromotionReviewController {
    private final PromotionReviewService service;
    private final BuildOperationsService buildOperations;
    private final PromotionValidationDailySummaryService validationDailySummaryService;

    public PromotionReviewController(PromotionReviewService service) {
        this(service, null, null);
    }

    @Autowired
    public PromotionReviewController(PromotionReviewService service, BuildOperationsService buildOperations,
                                     PromotionValidationDailySummaryService validationDailySummaryService) {
        this.service = service;
        this.buildOperations = buildOperations;
        this.validationDailySummaryService = validationDailySummaryService;
    }

    @PostMapping("/build")
    public BuildOperationResponse build(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (buildOperations == null) { var r = service.build(date); return BuildOperationResponse.builder("PROMOTION_REVIEW", date).builtCount(r.items().size()).safetyBoundary(PromotionReviewResponse.defaultSafetyBoundary()).payload(Map.of("items", r.items())).build(); }
        return buildOperations.buildPromotionReview(date);
    }

    @GetMapping("/queue")
    public PromotionReviewResponse queue(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.queue(date);
    }

    @GetMapping("/item/{id}")
    public PromotionReviewResponse.Item item(@PathVariable Long id) {
        return service.item(id);
    }

    @PostMapping("/item/{id}/decision")
    public PromotionReviewResponse.Item decide(@PathVariable Long id, @RequestBody PromotionReviewDecisionRequest request) {
        return service.decide(id, request);
    }

    @GetMapping("/audit")
    public PromotionReviewResponse.AuditResponse audit(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String symbol) {
        return service.audit(date, symbol);
    }

    @GetMapping("/policy-simulation")
    public PromotionPolicySimulationResponse policySimulation(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "CANDIDATE_POOL_SHADOW") String status) {
        return service.policySimulation(startDate, endDate, status);
    }

    @GetMapping("/validation-report")
    public PromotionValidationReportResponse validationReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "CANDIDATE_POOL_SHADOW") String status) {
        return service.validationReport(startDate, endDate, status);
    }

    @GetMapping("/graduation-readiness")
    public PromotionGraduationReadinessResponse graduationReadiness(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "CANDIDATE_POOL_SHADOW") String status) {
        return service.graduationReadiness(startDate, endDate, status);
    }

    @PostMapping("/forward-tracking-bridge")
    public Map<String, Object> forwardTrackingBridge(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "CANDIDATE_POOL_SHADOW") String status) {
        return service.bridgeForwardTracking(startDate, endDate, status);
    }

    @PostMapping("/daily-validation-summary")
    public Map<String, Object> dailyValidationSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "CANDIDATE_POOL_SHADOW") String status,
            @RequestParam(defaultValue = "14") int backfillDays) {
        if (validationDailySummaryService == null) {
            throw new IllegalStateException("PromotionValidationDailySummaryService is not available");
        }
        return validationDailySummaryService.run(date, status, backfillDays);
    }
}
