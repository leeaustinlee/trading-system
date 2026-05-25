package com.austin.trading.controller;

import com.austin.trading.dto.request.KolSignalCreateRequest;
import com.austin.trading.dto.response.KolShadowReportResponse;
import com.austin.trading.dto.response.NarrativeDashboardResponse;
import com.austin.trading.dto.response.NarrativeShadowReviewResponse;
import com.austin.trading.service.KolSignalIngestionService;
import com.austin.trading.service.KolSignalShadowModeService;
import com.austin.trading.service.NarrativeDashboardService;
import com.austin.trading.service.NarrativeShadowReviewService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/narrative")
public class NarrativeController {

    private final KolSignalIngestionService ingestionService;
    private final NarrativeDashboardService dashboardService;
    private final KolSignalShadowModeService shadowModeService;
    private final NarrativeShadowReviewService shadowReviewService;

    public NarrativeController(KolSignalIngestionService ingestionService,
                               NarrativeDashboardService dashboardService,
                               KolSignalShadowModeService shadowModeService,
                               NarrativeShadowReviewService shadowReviewService) {
        this.ingestionService = ingestionService;
        this.dashboardService = dashboardService;
        this.shadowModeService = shadowModeService;
        this.shadowReviewService = shadowReviewService;
    }

    @PostMapping("/transcripts")
    public ResponseEntity<?> submitTranscript(@RequestBody KolSignalCreateRequest request) {
        try {
            return ResponseEntity.ok(ingestionService.create(request));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/dashboard")
    public NarrativeDashboardResponse dashboard(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return dashboardService.dashboard(date != null ? date : LocalDate.now());
    }

    @PostMapping("/shadow/run")
    public KolShadowReportResponse shadowRun(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return shadowModeService.run(date);
    }

    @GetMapping("/shadow/report")
    public KolShadowReportResponse shadowReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return shadowModeService.report(date);
    }

    @GetMapping("/shadow/review")
    public NarrativeShadowReviewResponse shadowReview(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return shadowReviewService.report(date);
    }
}
