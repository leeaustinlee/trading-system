package com.austin.trading.controller;

import com.austin.trading.dto.response.NextDayStrategyDto;
import com.austin.trading.dto.response.PortfolioRotationShadowResponse;
import com.austin.trading.dto.response.PositionIntelligenceResultDto;
import com.austin.trading.service.NextDayStrategyBuilder;
import com.austin.trading.service.PortfolioHealthV2Service;
import com.austin.trading.service.PortfolioRotationShadowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioDecisionController {
    private final NextDayStrategyBuilder builder;
    private final PortfolioHealthV2Service healthV2Service;
    private final PortfolioRotationShadowService rotationShadowService;

    @Autowired
    public PortfolioDecisionController(NextDayStrategyBuilder builder,
                                       PortfolioHealthV2Service healthV2Service,
                                       PortfolioRotationShadowService rotationShadowService) {
        this.builder = builder;
        this.healthV2Service = healthV2Service;
        this.rotationShadowService = rotationShadowService;
    }

    public PortfolioDecisionController(NextDayStrategyBuilder builder, PortfolioHealthV2Service healthV2Service) {
        this(builder, healthV2Service, null);
    }

    public PortfolioDecisionController(NextDayStrategyBuilder builder) {
        this(builder, null, null);
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

    @GetMapping("/health-v2/data-gaps")
    public Map<String, Object> healthV2DataGaps() {
        if (healthV2Service == null) {
            throw new IllegalStateException("Portfolio health-v2 service is not available");
        }
        return healthV2Service.healthV2DataGaps();
    }

    @GetMapping("/health-v2/history")
    public Map<String, Object> healthV2History(@RequestParam(defaultValue = "30") int days,
                                               @RequestParam(required = false) String symbol) {
        if (healthV2Service == null) {
            throw new IllegalStateException("Portfolio health-v2 service is not available");
        }
        return healthV2Service.healthV2History(days, symbol);
    }

    @GetMapping("/rotation-shadow")
    public PortfolioRotationShadowResponse rotationShadow(@RequestParam(defaultValue = "60") int days) {
        if (rotationShadowService == null) {
            throw new IllegalStateException("Portfolio rotation-shadow service is not available");
        }
        return rotationShadowService.report(days);
    }

    @GetMapping("/next-day-strategy")
    public NextDayStrategyDto nextDayStrategy() {
        return builder.buildStrategy();
    }
}
