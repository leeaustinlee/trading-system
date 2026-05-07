package com.austin.trading.service;

import com.austin.trading.domain.enums.HoldDecision;
import com.austin.trading.domain.enums.PositionRiskLevel;
import com.austin.trading.domain.enums.PositionStrength;
import com.austin.trading.domain.enums.SwitchDecision;
import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.dto.response.PositionIntelligenceResultDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioSwitchAnalyzerTests {
    private final PortfolioSwitchAnalyzer analyzer = new PortfolioSwitchAnalyzer();

    @Test
    void neutralPositionWithStrongerCandidateProducesSwitchSuggestion() {
        var position = new PositionIntelligenceResultDto("6770", "力積電", PositionStrength.NEUTRAL,
                PositionRiskLevel.MEDIUM, HoldDecision.REDUCE, new BigDecimal("55"), new BigDecimal("65"), null, "中性");
        var candidate = candidate("8039", "台虹", "breakout", "78");

        var result = analyzer.analyzeSwitch(List.of(position), List.of(candidate));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).decision()).isIn(SwitchDecision.SWITCH, SwitchDecision.PARTIAL_SWITCH);
        assertThat(result.get(0).buyStockId()).isEqualTo("8039");
    }

    private CandidateResponse candidate(String symbol, String name, String strategy, String score) {
        return new CandidateResponse(LocalDate.of(2026, 5, 7), symbol, name, new BigDecimal(score), strategy,
                strategy, null, null, true, null, null, null, null, null, null, null, null,
                new BigDecimal(score), false, null, null, null);
    }
}
