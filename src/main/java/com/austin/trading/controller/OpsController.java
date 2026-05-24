package com.austin.trading.controller;

import com.austin.trading.service.OpsSummaryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/ops")
public class OpsController {
    private final OpsSummaryService service;

    public OpsController(OpsSummaryService service) {
        this.service = service;
    }

    @GetMapping("/daily-summary")
    public Map<String, Object> dailySummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.dailySummary(date);
    }

    @GetMapping("/build-traces")
    public Map<String, Object> buildTraces(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.buildTraces(date);
    }
}
