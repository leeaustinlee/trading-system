package com.austin.trading.service;

import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.StockEvaluationEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.StockEvaluationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class CandidateScanService {

    private final CandidateStockRepository candidateStockRepository;
    private final StockEvaluationRepository stockEvaluationRepository;

    public CandidateScanService(
            CandidateStockRepository candidateStockRepository,
            StockEvaluationRepository stockEvaluationRepository
    ) {
        this.candidateStockRepository = candidateStockRepository;
        this.stockEvaluationRepository = stockEvaluationRepository;
    }

    public List<FinalDecisionCandidateRequest> loadFinalDecisionCandidates(LocalDate tradingDate, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        List<CandidateStockEntity> candidates = candidateStockRepository.findByTradingDateOrderByScoreDesc(
                tradingDate,
                PageRequest.of(0, safeLimit)
        );

        Map<String, StockEvaluationEntity> evaluationBySymbol = new HashMap<>();
        for (StockEvaluationEntity e : stockEvaluationRepository.findByTradingDate(tradingDate)) {
            evaluationBySymbol.put(e.getSymbol(), e);
        }

        return candidates.stream()
                .map(c -> toFinalDecisionCandidate(c, evaluationBySymbol.get(c.getSymbol())))
                .toList();
    }

    public List<CandidateResponse> getCurrentCandidates(int limit) {
        return getCandidatesByDate(LocalDate.now(), limit);
    }

    public List<CandidateResponse> getCandidatesByDate(LocalDate tradingDate, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        List<CandidateStockEntity> candidates = candidateStockRepository.findByTradingDateOrderByScoreDesc(
                tradingDate,
                PageRequest.of(0, safeLimit)
        );
        return mergeWithEvaluation(candidates, tradingDate);
    }

    public List<CandidateResponse> getCandidatesHistory(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 300));
        List<CandidateStockEntity> candidates = candidateStockRepository.findAllByOrderByTradingDateDescScoreDesc(
                PageRequest.of(0, safeLimit)
        );
        return mergeWithEvaluation(candidates, null);
    }

    private List<CandidateResponse> mergeWithEvaluation(List<CandidateStockEntity> candidates, LocalDate filterDate) {
        Map<String, StockEvaluationEntity> evaluationBySymbol = new HashMap<>();
        if (filterDate != null) {
            for (StockEvaluationEntity e : stockEvaluationRepository.findByTradingDate(filterDate)) {
                evaluationBySymbol.put(filterDate + ":" + e.getSymbol(), e);
            }
        } else {
            Map<LocalDate, List<StockEvaluationEntity>> grouped = new HashMap<>();
            for (CandidateStockEntity c : candidates) {
                grouped.computeIfAbsent(c.getTradingDate(), d -> stockEvaluationRepository.findByTradingDate(d));
            }
            for (Map.Entry<LocalDate, List<StockEvaluationEntity>> entry : grouped.entrySet()) {
                for (StockEvaluationEntity e : entry.getValue()) {
                    evaluationBySymbol.put(entry.getKey() + ":" + e.getSymbol(), e);
                }
            }
        }

        return candidates.stream()
                .map(c -> {
                    StockEvaluationEntity eval = evaluationBySymbol.get(c.getTradingDate() + ":" + c.getSymbol());
                    return new CandidateResponse(
                            c.getTradingDate(),
                            c.getSymbol(),
                            c.getStockName(),
                            c.getScore(),
                            c.getReason(),
                            eval == null ? null : eval.getValuationMode(),
                            eval == null ? null : eval.getEntryPriceZone(),
                            eval == null ? null : eval.getRiskRewardRatio(),
                            eval == null ? null : eval.getIncludeInFinalPlan()
                    );
                })
                .toList();
    }

    private FinalDecisionCandidateRequest toFinalDecisionCandidate(
            CandidateStockEntity candidate,
            StockEvaluationEntity eval
    ) {
        BigDecimal rr = eval == null ? BigDecimal.ZERO : nullSafe(eval.getRiskRewardRatio(), BigDecimal.ZERO);
        boolean includePlan = eval != null && Boolean.TRUE.equals(eval.getIncludeInFinalPlan());
        String valuationMode = eval == null ? "VALUE_STORY" : nullSafe(eval.getValuationMode(), "VALUE_STORY");
        String entryType = inferEntryType(candidate.getReason());

        String entryZone = eval == null ? null : eval.getEntryPriceZone();
        Double stopLoss = eval == null || eval.getStopLossPrice() == null ? null : eval.getStopLossPrice().doubleValue();
        Double tp1 = eval == null || eval.getTakeProfit1() == null ? null : eval.getTakeProfit1().doubleValue();
        Double tp2 = eval == null || eval.getTakeProfit2() == null ? null : eval.getTakeProfit2().doubleValue();

        return new FinalDecisionCandidateRequest(
                candidate.getSymbol(),
                nullSafe(candidate.getStockName(), candidate.getSymbol()),
                valuationMode,
                entryType,
                rr.doubleValue(),
                includePlan,
                true,
                false,
                false,
                false,
                false,
                stopLoss != null,
                candidate.getReason(),
                entryZone,
                stopLoss,
                tp1,
                tp2
        );
    }

    private String inferEntryType(String reason) {
        if (reason == null) {
            return "PULLBACK";
        }
        String r = reason.toLowerCase(Locale.ROOT);
        if (r.contains("突破") || r.contains("breakout")) {
            return "BREAKOUT";
        }
        if (r.contains("轉強") || r.contains("reversal")) {
            return "REVERSAL";
        }
        return "PULLBACK";
    }

    private <T> T nullSafe(T value, T fallback) {
        return value == null ? fallback : value;
    }
}
