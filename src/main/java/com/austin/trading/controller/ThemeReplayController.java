package com.austin.trading.controller;

import com.austin.trading.dto.response.BuildOperationResponse;
import com.austin.trading.dto.response.ThemeReplaySummaryResponse;
import com.austin.trading.dto.response.ThemeReplayTimelineResponse;
import com.austin.trading.service.BuildOperationsService;
import com.austin.trading.service.ThemeReplayTimelineService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/theme-replay")
public class ThemeReplayController {

    private final ThemeReplayTimelineService service;
    private final BuildOperationsService buildOperations;

    public ThemeReplayController(ThemeReplayTimelineService service) {
        this(service, null);
    }

    @Autowired
    public ThemeReplayController(ThemeReplayTimelineService service, BuildOperationsService buildOperations) {
        this.service = service;
        this.buildOperations = buildOperations;
    }

    @GetMapping("/dates")
    public Map<String, List<LocalDate>> dates() {
        return Map.of("dates", service.dates());
    }

    @GetMapping
    public Map<String, Object> summaries(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<ThemeReplaySummaryResponse> themes = service.summaries(date);
        return Map.of(
                "tradingDate", date,
                "shadowOnly", true,
                "replayOnly", true,
                "safetyBoundary", ThemeReplayTimelineResponse.SafetyBoundary.replayOnlyBoundary(),
                "themes", themes
        );
    }

    @GetMapping("/themes/{themeTag}/timeline")
    public ThemeReplayTimelineResponse timeline(
            @PathVariable String themeTag,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.timeline(date, themeTag);
    }

    @PostMapping("/build")
    public BuildOperationResponse build(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (buildOperations == null) { service.build(date); return BuildOperationResponse.builder("THEME_REPLAY", date).builtCount(0).safetyBoundary(ThemeReplayTimelineResponse.SafetyBoundary.replayOnlyBoundary()).build(); }
        return buildOperations.buildThemeReplay(date);
    }
}
