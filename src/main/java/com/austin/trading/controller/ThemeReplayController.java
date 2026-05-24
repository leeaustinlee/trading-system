package com.austin.trading.controller;

import com.austin.trading.dto.response.ThemeReplaySummaryResponse;
import com.austin.trading.dto.response.ThemeReplayTimelineResponse;
import com.austin.trading.service.ThemeReplayTimelineService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/theme-replay")
public class ThemeReplayController {

    private final ThemeReplayTimelineService service;

    public ThemeReplayController(ThemeReplayTimelineService service) {
        this.service = service;
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
    public ThemeReplayTimelineResponse build(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.build(date);
    }
}
