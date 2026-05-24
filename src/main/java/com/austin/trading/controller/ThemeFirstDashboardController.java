package com.austin.trading.controller;

import com.austin.trading.service.ThemeFirstDashboardService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@RestController
public class ThemeFirstDashboardController {
    private final ThemeFirstDashboardService service;

    public ThemeFirstDashboardController(ThemeFirstDashboardService service) {
        this.service = service;
    }

    @GetMapping(value = "/dashboard/theme-first", produces = MediaType.TEXT_HTML_VALUE)
    public String themeFirstDashboard(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.renderHtml(date);
    }

    @GetMapping("/api/dashboard/theme-first")
    public Map<String, Object> themeFirstDashboardApi(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.readOnlyMetadata(date);
    }
}
