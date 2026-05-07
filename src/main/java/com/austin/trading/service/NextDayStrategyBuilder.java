package com.austin.trading.service;

import com.austin.trading.domain.enums.HoldDecision;
import com.austin.trading.domain.enums.MarketBias;
import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.dto.response.NextDayStrategyDto;
import com.austin.trading.dto.response.PortfolioSwitchSuggestionDto;
import com.austin.trading.dto.response.PositionIntelligenceResultDto;
import com.austin.trading.engine.PositionIntelligenceEngine;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.PositionDailyReviewEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.PositionDailyReviewRepository;
import com.austin.trading.repository.PositionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
public class NextDayStrategyBuilder {
    private final PositionRepository positionRepository;
    private final CandidateStockRepository candidateStockRepository;
    private final PositionDailyReviewRepository reviewRepository;
    private final PositionIntelligenceEngine positionIntelligenceEngine;
    private final PortfolioSwitchAnalyzer switchAnalyzer;

    public NextDayStrategyBuilder(PositionRepository positionRepository,
                                  CandidateStockRepository candidateStockRepository,
                                  PositionDailyReviewRepository reviewRepository,
                                  PositionIntelligenceEngine positionIntelligenceEngine,
                                  PortfolioSwitchAnalyzer switchAnalyzer) {
        this.positionRepository = positionRepository;
        this.candidateStockRepository = candidateStockRepository;
        this.reviewRepository = reviewRepository;
        this.positionIntelligenceEngine = positionIntelligenceEngine;
        this.switchAnalyzer = switchAnalyzer;
    }

    public List<PositionIntelligenceResultDto> reviewPositions() {
        List<PositionIntelligenceResultDto> rows = positionRepository.findByStatus("OPEN").stream()
                .map(positionIntelligenceEngine::evaluatePosition)
                .toList();
        persistDailyReview(rows, LocalDate.now());
        return rows;
    }

    public NextDayStrategyDto buildStrategy() {
        LocalDate tradingDate = candidateStockRepository.findTopByOrderByTradingDateDesc()
                .map(CandidateStockEntity::getTradingDate)
                .orElse(LocalDate.now());
        List<PositionIntelligenceResultDto> positions = reviewPositions();
        List<CandidateResponse> candidates = loadCandidates(tradingDate);
        List<PortfolioSwitchSuggestionDto> switches = switchAnalyzer.analyzeSwitch(positions, candidates);
        MarketBias bias = marketBias(positions, switches);
        return new NextDayStrategyDto(
                tradingDate,
                positions,
                switches,
                bias,
                actionPlan(positions, switches, bias),
                "所有內容僅供 Austin 人工決策；系統不會自動下單、賣出或回滾持倉。"
        );
    }

    private List<CandidateResponse> loadCandidates(LocalDate tradingDate) {
        return candidateStockRepository.findByTradingDateOrderByScoreDesc(tradingDate, PageRequest.of(0, 10)).stream()
                .map(this::toCandidateResponse)
                .toList();
    }

    private CandidateResponse toCandidateResponse(CandidateStockEntity e) {
        return new CandidateResponse(
                e.getTradingDate(), e.getSymbol(), e.getStockName(), e.getScore(), e.getReason(), null,
                null, null, true, null, null, null, e.getThemeTag(), e.getSector(), null,
                null, null, e.getScore(), false, null, null, null
        );
    }

    private MarketBias marketBias(List<PositionIntelligenceResultDto> positions, List<PortfolioSwitchSuggestionDto> switches) {
        long exitOrReduce = positions.stream().filter(p -> p.holdDecision() == HoldDecision.EXIT || p.holdDecision() == HoldDecision.REDUCE).count();
        long highHold = positions.stream().filter(p -> p.holdDecision() == HoldDecision.HIGH_HOLD).count();
        if (exitOrReduce > 0 && switches.isEmpty()) return MarketBias.DEFENSIVE;
        if (highHold > 0 && !switches.isEmpty()) return MarketBias.OFFENSIVE;
        return MarketBias.WATCH;
    }

    private String actionPlan(List<PositionIntelligenceResultDto> positions,
                              List<PortfolioSwitchSuggestionDto> switches,
                              MarketBias bias) {
        long hold = positions.stream().filter(p -> p.holdDecision() == HoldDecision.HIGH_HOLD || p.holdDecision() == HoldDecision.HOLD).count();
        long exit = positions.stream().filter(p -> p.holdDecision() == HoldDecision.EXIT).count();
        return "明日偏" + switch (bias) {
            case OFFENSIVE -> "進攻";
            case DEFENSIVE -> "防守";
            case WATCH -> "觀望";
        } + "；續抱=" + hold + "，退出觀察=" + exit + "，換股建議=" + switches.size()
                + "；所有 stop 僅建議上修，不自動下修。";
    }

    private void persistDailyReview(List<PositionIntelligenceResultDto> rows, LocalDate tradingDate) {
        rows.forEach(r -> {
            PositionDailyReviewEntity e = new PositionDailyReviewEntity();
            e.setTradingDate(tradingDate);
            e.setStockId(r.stockId());
            e.setStrength(r.strength().name());
            e.setRisk(riskName(r));
            e.setHoldDecision(r.holdDecision().name());
            e.setSuggestedStop(r.suggestedStop());
            e.setSuggestedTakeProfit(r.suggestedTakeProfit());
            e.setSwitchFlag(r.switchDecision() == null ? "KEEP" : r.switchDecision().name());
            e.setReason(r.reason());
            reviewRepository.save(e);
        });
    }

    private String riskName(PositionIntelligenceResultDto r) {
        return r.risk() == null ? null : r.risk().name();
    }
}
