package com.austin.trading.controller;

import com.austin.trading.dto.response.ThemeLifecycleAnnotationResponse;
import com.austin.trading.service.ThemeLifecycleAnnotationService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/theme-lifecycle/annotations")
public class ThemeLifecycleAnnotationController {
    private final ThemeLifecycleAnnotationService service;

    public ThemeLifecycleAnnotationController(ThemeLifecycleAnnotationService service) {
        this.service = service;
    }

    @GetMapping("/candidates")
    public ThemeLifecycleAnnotationResponse candidates(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.candidates(date);
    }

    @GetMapping("/watchlist")
    public ThemeLifecycleAnnotationResponse watchlist(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.watchlist(date);
    }

    @GetMapping("/ranking")
    public ThemeLifecycleAnnotationResponse ranking(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.ranking(date);
    }

    @GetMapping("/positions")
    public ThemeLifecycleAnnotationResponse positions(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.positions(date);
    }
}
