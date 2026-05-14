package com.austin.trading.controller;

import com.austin.trading.service.MainstreamOverlapReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/mainstream/overlap")
public class MainstreamOverlapController {
    private final MainstreamOverlapReportService service;

    public MainstreamOverlapController(MainstreamOverlapReportService service) {
        this.service = service;
    }

    @GetMapping("/recent")
    public Map<String, Object> recent(@RequestParam(defaultValue = "30") int days) {
        return service.recent(days);
    }
}
