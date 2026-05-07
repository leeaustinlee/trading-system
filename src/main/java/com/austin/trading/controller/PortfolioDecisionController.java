package com.austin.trading.controller;

import com.austin.trading.dto.response.NextDayStrategyDto;
import com.austin.trading.dto.response.PositionIntelligenceResultDto;
import com.austin.trading.service.NextDayStrategyBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioDecisionController {
    private final NextDayStrategyBuilder builder;

    public PortfolioDecisionController(NextDayStrategyBuilder builder) {
        this.builder = builder;
    }

    @GetMapping("/review")
    public List<PositionIntelligenceResultDto> review() {
        return builder.reviewPositions();
    }

    @GetMapping("/next-day-strategy")
    public NextDayStrategyDto nextDayStrategy() {
        return builder.buildStrategy();
    }
}
