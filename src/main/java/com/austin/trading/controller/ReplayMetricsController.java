package com.austin.trading.controller;

import com.austin.trading.dto.response.ThemeReplayMetricsResponse;
import com.austin.trading.service.ReplayMetricsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/replay-metrics")
public class ReplayMetricsController {

    private final ReplayMetricsService service;

    public ReplayMetricsController(ReplayMetricsService service) {
        this.service = service;
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
    public ThemeReplayMetricsResponse.BuildResult build(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.build(date);
    }

    @GetMapping("/safety-summary")
    public ThemeReplayMetricsResponse.SafetySummary safetySummary(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.safetySummary(date);
    }
}
