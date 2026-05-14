package com.austin.trading.service;

import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.PaperTradeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class CandidateForwardTrackingService {
    private final CandidateForwardTrackingRepository repository;
    private final PaperTradeRepository paperTradeRepository;

    public CandidateForwardTrackingService(CandidateForwardTrackingRepository repository,
                                           PaperTradeRepository paperTradeRepository) {
        this.repository = repository;
        this.paperTradeRepository = paperTradeRepository;
    }

    public Map<String, Object> summary() {
        long candidateRows = repository.count();
        long paperRows = paperTradeRepository.count();
        boolean usingPaperFallback = candidateRows == 0 && paperRows > 0;
        long total = usingPaperFallback ? paperRows : candidateRows;
        return Map.of(
                "total", total,
                "candidateRows", candidateRows,
                "paperTradeRows", paperRows,
                "source", usingPaperFallback ? "PAPER_TRADE_FALLBACK" : "CANDIDATE_FORWARD_TRACKING",
                "status", usingPaperFallback
                        ? "DATA_GAP: candidate_forward_tracking empty; paper_trade fallback available"
                        : "OK"
        );
    }
    public List<Map<String, Object>> byDecision() { return repository.byDecision(); }
    public List<Map<String, Object>> byGrade() { return repository.byGrade(); }
    public List<Map<String, Object>> byStrategy() { return repository.byStrategy(); }
    public List<Map<String, Object>> byGate() { return repository.byGate(); }

    @Transactional
    public Map<String, Object> backfillFromPaperTrades(int days) {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(days > 0 ? days : 30);
        List<PaperTradeEntity> trades = paperTradeRepository.findByEntryDateBetweenOrderByEntryDateAscIdAsc(start, end);
        int written = 0;
        for (PaperTradeEntity t : trades) {
            String decision = "PAPER_" + (t.isShadow() ? "SHADOW" : "ENTER");
            if (repository.findByTradingDateAndStockIdAndFinalDecision(
                    t.getEntryDate(), t.getSymbol(), decision).isPresent()) {
                continue;
            }
            CandidateForwardTrackingEntity row = new CandidateForwardTrackingEntity();
            row.setTradingDate(t.getEntryDate());
            row.setStockId(t.getSymbol());
            row.setStockName(t.getStockName());
            row.setFinalDecision(decision);
            row.setFinalScore(t.getFinalRankScore());
            row.setGrade(t.getEntryGrade());
            row.setPrimaryStrategy(t.getStrategyType());
            row.setGateName(t.getSanityResult());
            row.setEntryPriceAtDecision(t.getEntryPrice());
            row.setT1CloseReturnPct(t.getReturn1d());
            row.setT3CloseReturnPct(t.getReturn3d());
            row.setT5CloseReturnPct(t.getReturn5d());
            row.setT10CloseReturnPct(t.getReturn10d());
            row.setMfePct(t.getMfePct());
            row.setMaePct(t.getMaePct());
            repository.save(row);
            written++;
        }
        return Map.of("written", written, "sourcePaperTrades", trades.size(), "start", start, "end", end);
    }
}
