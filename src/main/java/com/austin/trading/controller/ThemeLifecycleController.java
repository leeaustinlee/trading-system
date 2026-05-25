package com.austin.trading.controller;

import com.austin.trading.dto.response.BuildOperationResponse;
import com.austin.trading.dto.response.ThemeLifecycleResponse;
import com.austin.trading.service.BuildOperationsService;
import com.austin.trading.service.ThemeLifecycleEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/themes/lifecycle")
public class ThemeLifecycleController {

    private final ThemeLifecycleEngine engine;
    private final BuildOperationsService buildOperations;

    public ThemeLifecycleController(ThemeLifecycleEngine engine) {
        this(engine, null);
    }

    @Autowired
    public ThemeLifecycleController(ThemeLifecycleEngine engine, BuildOperationsService buildOperations) {
        this.engine = engine;
        this.buildOperations = buildOperations;
    }

    @GetMapping
    public ThemeLifecycleResponse get(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return engine.get(date);
    }

    @GetMapping("/{themeTag}")
    public ThemeLifecycleResponse getTheme(
            @PathVariable String themeTag,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return engine.getTheme(date, themeTag);
    }

    @PostMapping("/build")
    public BuildOperationResponse build(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (buildOperations == null) { var r = engine.build(date); return BuildOperationResponse.builder("LIFECYCLE", date).builtCount(r.builtCount()).safetyBoundary(ThemeLifecycleResponse.SafetyBoundary.lifecycleReplayOnlyBoundary()).payload(Map.of("stages", r.stages())).build(); }
        return buildOperations.buildLifecycle(date);
    }
}
