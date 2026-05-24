package com.austin.trading.controller;

import com.austin.trading.dto.response.BuildOperationResponse;
import com.austin.trading.dto.response.ResearchUniverseResponse;
import com.austin.trading.service.BuildOperationsService;
import com.austin.trading.service.ResearchUniverseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/research-universe")
public class ResearchUniverseController {

    private final ResearchUniverseService service;
    private final BuildOperationsService buildOperations;

    public ResearchUniverseController(ResearchUniverseService service) {
        this(service, null);
    }

    @Autowired
    public ResearchUniverseController(ResearchUniverseService service, BuildOperationsService buildOperations) {
        this.service = service;
        this.buildOperations = buildOperations;
    }

    @GetMapping
    public ResearchUniverseResponse items(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.get(date);
    }

    @GetMapping("/themes/{themeTag}")
    public ResearchUniverseResponse theme(
            @PathVariable String themeTag,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.byTheme(date, themeTag);
    }

    @GetMapping("/symbol/{symbol}")
    public ResearchUniverseResponse symbol(
            @PathVariable String symbol,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.bySymbol(date, symbol);
    }

    @PostMapping("/build")
    public BuildOperationResponse build(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (buildOperations == null) { var r = service.build(date); return BuildOperationResponse.builder("RESEARCH_UNIVERSE", date).builtCount(r.items().size()).safetyBoundary(ResearchUniverseResponse.SafetyBoundary.researchOnlyBoundary()).build(); }
        return buildOperations.buildResearchUniverse(date);
    }

    @GetMapping("/governance-summary")
    public ResearchUniverseResponse.GovernanceSummary governanceSummary(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.governanceSummary(date);
    }
}
