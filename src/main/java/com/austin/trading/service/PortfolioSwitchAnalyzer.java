package com.austin.trading.service;

import com.austin.trading.domain.enums.HoldDecision;
import com.austin.trading.domain.enums.SwitchDecision;
import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.dto.response.PortfolioSwitchSuggestionDto;
import com.austin.trading.dto.response.PositionIntelligenceResultDto;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class PortfolioSwitchAnalyzer {
    private static final BigDecimal SWITCH_SCORE_GAP = new BigDecimal("8");
    private static final BigDecimal PARTIAL_SCORE_GAP = new BigDecimal("5");

    public List<PortfolioSwitchSuggestionDto> analyzeSwitch(List<PositionIntelligenceResultDto> positions,
                                                            List<CandidateResponse> candidates) {
        if (positions == null || positions.isEmpty() || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Optional<CandidateResponse> best = candidates.stream()
                .filter(c -> c.symbol() != null)
                .filter(c -> c.isVetoed() == null || !c.isVetoed())
                .max(Comparator.comparing(c -> nz(c.finalRankScore(), c.score())));
        if (best.isEmpty()) return List.of();
        CandidateResponse candidate = best.get();
        BigDecimal candidateScore = nz(candidate.finalRankScore(), candidate.score());

        return positions.stream()
                .filter(p -> p.holdDecision() == HoldDecision.HOLD || p.holdDecision() == HoldDecision.REDUCE || p.holdDecision() == HoldDecision.EXIT)
                .filter(p -> !candidate.symbol().equals(p.stockId()))
                .map(p -> suggestion(p, candidate, candidateScore))
                .filter(s -> s != null)
                .toList();
    }

    private PortfolioSwitchSuggestionDto suggestion(PositionIntelligenceResultDto position,
                                                   CandidateResponse candidate,
                                                   BigDecimal candidateScore) {
        BigDecimal base = position.holdDecision() == HoldDecision.EXIT ? new BigDecimal("60") :
                position.holdDecision() == HoldDecision.REDUCE ? new BigDecimal("65") : new BigDecimal("70");
        BigDecimal gap = candidateScore.subtract(base);
        SwitchDecision decision;
        if (position.holdDecision() == HoldDecision.EXIT && gap.compareTo(PARTIAL_SCORE_GAP) >= 0) {
            decision = SwitchDecision.SWITCH;
        } else if ((position.holdDecision() == HoldDecision.REDUCE || position.holdDecision() == HoldDecision.HOLD)
                && gap.compareTo(SWITCH_SCORE_GAP) >= 0) {
            decision = SwitchDecision.PARTIAL_SWITCH;
        } else {
            return null;
        }
        return new PortfolioSwitchSuggestionDto(
                position.stockId(),
                candidate.symbol(),
                candidate.stockName(),
                inferStrategy(candidate),
                decision,
                gap,
                "僅建議人工換股評估，不自動下單；需確認即時報價與風險",
                position.stockId() + " 續抱品質=" + position.holdDecision() + "，候選 " + candidate.symbol()
                        + " 分數較高，scoreGap=" + gap
        );
    }

    private String inferStrategy(CandidateResponse c) {
        String raw = String.join(" ",
                c.reason() == null ? "" : c.reason(),
                c.valuationMode() == null ? "" : c.valuationMode(),
                c.themeTag() == null ? "" : c.themeTag()).toLowerCase();
        if (raw.contains("breakout") || raw.contains("突破")) return "breakout";
        if (raw.contains("pullback") || raw.contains("回測") || raw.contains("低吸")) return "pullback";
        return "continuation";
    }

    private BigDecimal nz(BigDecimal primary, BigDecimal fallback) {
        if (primary != null) return primary;
        if (fallback != null) return fallback;
        return BigDecimal.ZERO;
    }
}
