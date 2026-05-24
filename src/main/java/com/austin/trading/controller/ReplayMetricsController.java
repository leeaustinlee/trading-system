package com.austin.trading.controller;

import com.austin.trading.dto.response.BuildOperationResponse;
import com.austin.trading.dto.response.ThemeReplayMetricsResponse;
import com.austin.trading.service.BuildOperationsService;
import com.austin.trading.service.ReplayMetricsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/replay-metrics")
public class ReplayMetricsController {

    private final ReplayMetricsService service;
    private final BuildOperationsService buildOperations;

    public ReplayMetricsController(ReplayMetricsService service) {
        this(service, null);
    }

    @Autowired
    public ReplayMetricsController(ReplayMetricsService service, BuildOperationsService buildOperations) {
        this.service = service;
        this.buildOperations = buildOperations;
    }

    @GetMapping
    public ThemeReplayMetricsResponse get(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.get(date);
    }

    @GetMapping("/themes/{themeTag}")
    public ThemeReplayMetricsResponse theme(
            @PathVariable String themeTag,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.byTheme(date, themeTag);
    }

    @PostMapping("/build")
    public BuildOperationResponse build(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (buildOperations == null) { var r = service.build(date); return BuildOperationResponse.builder("REPLAY_METRICS", date).builtCount(r.builtCount()).safetyBoundary(ThemeReplayMetricsResponse.SafetyBoundary.replayAnalyticsOnlyBoundary()).payload(java.util.Map.of("metrics", r.metrics())).build(); }
        return buildOperations.buildReplayMetrics(date);
    }

    @GetMapping("/safety-summary")
    public ThemeReplayMetricsResponse.SafetySummary safetySummary(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.safetySummary(date);
    }
}
