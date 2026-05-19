package com.austin.trading.controller;

import com.austin.trading.dto.response.NextDayStrategyDto;
import com.austin.trading.dto.response.PositionIntelligenceResultDto;
import com.austin.trading.service.NextDayStrategyBuilder;
import com.austin.trading.service.PortfolioHealthV2Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioDecisionController {
    private final NextDayStrategyBuilder builder;
    private final PortfolioHealthV2Service healthV2Service;

    @Autowired
    public PortfolioDecisionController(NextDayStrategyBuilder builder, PortfolioHealthV2Service healthV2Service) {
        this.builder = builder;
        this.healthV2Service = healthV2Service;
    }

    public PortfolioDecisionController(NextDayStrategyBuilder builder) {
        this(builder, null);
    }

    @GetMapping("/review")
    public List<PositionIntelligenceResultDto> review() {
        return builder.reviewPositions();
    }

    @GetMapping("/health-v2")
    public Map<String, Object> healthV2() {
        if (healthV2Service == null) {
            throw new IllegalStateException("Portfolio health-v2 service is not available");
        }
        return healthV2Service.healthV2();
    }

    @GetMapping("/next-day-strategy")
    public NextDayStrategyDto nextDayStrategy() {
        return builder.buildStrategy();
    }
}
