package com.austin.trading.dto.response;

import com.austin.trading.domain.enums.HoldDecision;
import com.austin.trading.domain.enums.PositionRiskLevel;
import com.austin.trading.domain.enums.PositionStrength;
import com.austin.trading.domain.enums.SwitchDecision;

import java.math.BigDecimal;

public record PositionIntelligenceResultDto(
        String stockId,
        String stockName,
        PositionStrength strength,
        PositionRiskLevel risk,
        HoldDecision holdDecision,
        BigDecimal suggestedStop,
        BigDecimal suggestedTakeProfit,
        SwitchDecision switchDecision,
        String reason
) {
}
