package com.austin.trading.controller;

import com.austin.trading.dto.response.NarrativeThesisResponse;
import com.austin.trading.dto.response.ThemeContextSnapshot;
import com.austin.trading.service.NarrativeThesisService;
import com.austin.trading.service.ThemeIntelligenceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ThemeIntelligenceController {
    private final ThemeIntelligenceService themeIntelligenceService;
    private final NarrativeThesisService narrativeThesisService;

    public ThemeIntelligenceController(ThemeIntelligenceService themeIntelligenceService,
                                       NarrativeThesisService narrativeThesisService) {
        this.themeIntelligenceService = themeIntelligenceService;
        this.narrativeThesisService = narrativeThesisService;
    }

    @GetMapping("/theme-intelligence/summary")
    public List<ThemeContextSnapshot> summary() {
        return themeIntelligenceService.summary();
    }

    @GetMapping("/theme-intelligence/{theme}")
    public ThemeContextSnapshot theme(@PathVariable String theme) {
        return themeIntelligenceService.context(theme);
    }

    @GetMapping("/narrative-thesis/open-positions")
    public NarrativeThesisResponse narrativeOpenPositions() {
        return narrativeThesisService.openPositions();
    }
}
