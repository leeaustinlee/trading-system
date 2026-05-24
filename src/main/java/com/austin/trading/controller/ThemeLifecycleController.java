package com.austin.trading.controller;

import com.austin.trading.dto.response.ThemeLifecycleResponse;
import com.austin.trading.service.ThemeLifecycleEngine;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/themes/lifecycle")
public class ThemeLifecycleController {

    private final ThemeLifecycleEngine engine;

    public ThemeLifecycleController(ThemeLifecycleEngine engine) {
        this.engine = engine;
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
    public ThemeLifecycleResponse.BuildResult build(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return engine.build(date);
    }
}
