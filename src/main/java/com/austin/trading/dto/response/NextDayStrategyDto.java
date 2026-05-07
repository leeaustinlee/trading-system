package com.austin.trading.dto.response;

import com.austin.trading.domain.enums.MarketBias;

import java.time.LocalDate;
import java.util.List;

public record NextDayStrategyDto(
        LocalDate tradingDate,
        List<PositionIntelligenceResultDto> positionsSummary,
        List<PortfolioSwitchSuggestionDto> switchPlan,
        MarketBias marketBias,
        String actionPlan,
        String humanOnlyWarning
) {
}
