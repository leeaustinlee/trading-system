package com.austin.trading.controller;

import com.austin.trading.dto.response.ResearchUniverseResponse;
import com.austin.trading.service.ResearchUniverseService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/research-universe")
public class ResearchUniverseController {

    private final ResearchUniverseService service;

    public ResearchUniverseController(ResearchUniverseService service) {
        this.service = service;
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

    @GetMapping("/governance-summary")
    public ResearchUniverseResponse.GovernanceSummary governanceSummary(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.governanceSummary(date);
    }
}
