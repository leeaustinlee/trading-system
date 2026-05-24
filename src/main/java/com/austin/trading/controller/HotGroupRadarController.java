package com.austin.trading.controller;

import com.austin.trading.dto.response.HotGroupRadarResponse;
import com.austin.trading.service.HotGroupRadarService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/hot-groups")
public class HotGroupRadarController {
    private final HotGroupRadarService service;

    public HotGroupRadarController(HotGroupRadarService service) {
        this.service = service;
    }

    @PostMapping("/build")
    public HotGroupRadarResponse build(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "POSTMARKET") String phase) {
        return service.buildFromDefaultFile(date, phase);
    }

    @GetMapping("/radar")
    public HotGroupRadarResponse radar(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "POSTMARKET") String phase) {
        return service.radar(date, phase);
    }

    @GetMapping("/by-theme")
    public HotGroupRadarResponse byTheme(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam String themeTag) {
        return service.byTheme(date, themeTag);
    }

    @GetMapping("/explain-miss")
    public HotGroupRadarResponse.ExplainMiss explainMiss(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam String symbol) {
        return service.explainMiss(date, symbol);
    }

    @GetMapping("/candidate-feed")
    public HotGroupRadarResponse.CandidateFeed candidateFeed(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "POSTMARKET") String phase) {
        return service.candidateFeed(date, phase);
    }
}
