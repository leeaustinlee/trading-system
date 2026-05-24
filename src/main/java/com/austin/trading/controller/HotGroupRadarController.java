package com.austin.trading.controller;

import com.austin.trading.dto.response.BuildOperationResponse;
import com.austin.trading.dto.response.HotGroupRadarResponse;
import com.austin.trading.service.BuildOperationsService;
import com.austin.trading.service.HotGroupRadarService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/hot-groups")
public class HotGroupRadarController {
    private final HotGroupRadarService service;
    private final BuildOperationsService buildOperations;

    public HotGroupRadarController(HotGroupRadarService service) {
        this(service, null);
    }

    @Autowired
    public HotGroupRadarController(HotGroupRadarService service, BuildOperationsService buildOperations) {
        this.service = service;
        this.buildOperations = buildOperations;
    }

    @PostMapping("/build")
    public BuildOperationResponse build(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "POSTMARKET") String phase) {
        if (buildOperations == null) { var r = service.buildFromDefaultFile(date, phase); return BuildOperationResponse.builder("HOT_GROUP_RADAR", date).sourcePhase(phase).builtCount(r.themes().size() + r.signals().size()).safetyBoundary(r.safetyBoundary()).build(); }
        return buildOperations.buildHotGroups(date, phase);
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

    @GetMapping("/theme-members")
    public HotGroupRadarResponse themeMembers(
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
