package com.austin.trading.service;

import com.austin.trading.domain.enums.TradingFunnelBlockedStage;
import com.austin.trading.entity.*;
import com.austin.trading.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Builds read-only/shadow funnel traces from existing production artifacts. */
@Service
public class TradingFunnelTraceService {

    private static final Logger log = LoggerFactory.getLogger(TradingFunnelTraceService.class);

    private final HotGroupStockSignalRepository signalRepository;
    private final TradingFunnelTraceRepository traceRepository;
    private final CandidateStockRepository candidateRepository;
    private final WatchlistStockRepository watchlistRepository;
    private final StockRankingSnapshotRepository rankingRepository;
    private final PortfolioRiskDecisionRepository riskRepository;
    private final PaperTradeRepository paperTradeRepository;
    private final CandidateForwardTrackingRepository forwardTrackingRepository;

    public TradingFunnelTraceService(HotGroupStockSignalRepository signalRepository,
                                     TradingFunnelTraceRepository traceRepository,
                                     CandidateStockRepository candidateRepository,
                                     WatchlistStockRepository watchlistRepository,
                                     StockRankingSnapshotRepository rankingRepository,
                                     PortfolioRiskDecisionRepository riskRepository,
                                     PaperTradeRepository paperTradeRepository,
                                     CandidateForwardTrackingRepository forwardTrackingRepository) {
        this.signalRepository = signalRepository;
        this.traceRepository = traceRepository;
        this.candidateRepository = candidateRepository;
        this.watchlistRepository = watchlistRepository;
        this.rankingRepository = rankingRepository;
        this.riskRepository = riskRepository;
        this.paperTradeRepository = paperTradeRepository;
        this.forwardTrackingRepository = forwardTrackingRepository;
    }

    public void safeRebuildForDate(LocalDate tradingDate) {
        try {
            rebuildForDate(tradingDate);
        } catch (Exception ex) {
            log.warn("Trading funnel trace rebuild failed for {}: {}", tradingDate, ex.toString());
        }
    }

    @Transactional
    public int rebuildForDate(LocalDate tradingDate) {
        if (tradingDate == null) return 0;
        List<HotGroupStockSignalEntity> signals = signalRepository.findByTradingDateOrderByRadarRankScoreDesc(tradingDate);
        int saved = 0;
        for (HotGroupStockSignalEntity signal : signals) {
            if (isBlank(signal.getSymbol())) continue;
            TradingFunnelTraceEntity trace = traceRepository
                    .findByTradingDateAndSymbolAndSignalId(tradingDate, signal.getSymbol(), signal.getId())
                    .orElseGet(TradingFunnelTraceEntity::new);
            applySignal(trace, signal, tradingDate);
            enrichCandidate(trace, tradingDate, signal.getSymbol());
            enrichWatchlist(trace, signal.getSymbol());
            enrichRanking(trace, tradingDate, signal.getSymbol());
            enrichRisk(trace, tradingDate, signal.getSymbol());
            enrichBuyAndReturns(trace, tradingDate, signal.getSymbol());
            enrichForwardTracking(trace, tradingDate, signal.getSymbol());
            resolveBlockedStage(trace);
            trace.setTraceSource("SHADOW");
            trace.setTraceStatus("ACTIVE");
            traceRepository.save(trace);
            saved++;
        }
        return saved;
    }

    private void applySignal(TradingFunnelTraceEntity trace, HotGroupStockSignalEntity signal, LocalDate tradingDate) {
        trace.setTradingDate(tradingDate);
        trace.setSymbol(signal.getSymbol());
        trace.setStockName(signal.getStockName());
        trace.setThemeTag(signal.getThemeTag());
        trace.setSignalId(signal.getId());
        trace.setSignalSource(signal.getSourcePhase());
        trace.setSignalRole(signal.getRole());
        trace.setSignalStrength(signal.getRadarRankScore());
        trace.setSignalChangePct(signal.getChangePct());
        trace.setSignalNearLimit(Boolean.TRUE.equals(signal.getLimitRisk())
                || (signal.getChangePct() != null && signal.getChangePct().compareTo(new java.math.BigDecimal("7")) >= 0));
        trace.setSignalLimitRisk(String.valueOf(Boolean.TRUE.equals(signal.getLimitRisk())));
    }

    private void enrichCandidate(TradingFunnelTraceEntity trace, LocalDate date, String symbol) {
        candidateRepository.findByTradingDateAndSymbol(date, symbol).ifPresentOrElse(c -> {
            trace.setCandidateStatus("HIT");
            trace.setCandidateReason(c.getReason());
            trace.setCandidateId(c.getId());
        }, () -> trace.setCandidateStatus("MISS"));
    }

    private void enrichWatchlist(TradingFunnelTraceEntity trace, String symbol) {
        watchlistRepository.findBySymbol(symbol).ifPresentOrElse(w -> {
            trace.setWatchlistStatus(w.getWatchStatus());
            trace.setWatchlistReason(w.getDropReason());
            trace.setWatchlistId(w.getId());
        }, () -> trace.setWatchlistStatus("MISS"));
    }

    private void enrichRanking(TradingFunnelTraceEntity trace, LocalDate date, String symbol) {
        List<StockRankingSnapshotEntity> ranked = rankingRepository.findByTradingDate(date);
        for (int i = 0; i < ranked.size(); i++) {
            StockRankingSnapshotEntity r = ranked.get(i);
            if (symbol.equals(r.getSymbol())) {
                trace.setRankingStatus(r.isEligibleForSetup() ? "ELIGIBLE" : (r.isVetoed() ? "VETOED" : "RANKED"));
                trace.setRankingRank(i + 1);
                trace.setRankingScore(r.getSelectionScore());
                trace.setRankingReason(r.getRejectionReason());
                trace.setRankingSnapshotId(r.getId());
                return;
            }
        }
        trace.setRankingStatus("MISS");
    }

    private void enrichRisk(TradingFunnelTraceEntity trace, LocalDate date, String symbol) {
        riskRepository.findTopByTradingDateAndSymbolOrderByIdDesc(date, symbol).ifPresentOrElse(r -> {
            trace.setRiskStatus(r.isApproved() ? "APPROVED" : "BLOCKED");
            trace.setRiskReason(r.getBlockReason());
            trace.setRiskDecisionId(r.getId());
        }, () -> trace.setRiskStatus("MISS"));
    }

    private void enrichBuyAndReturns(TradingFunnelTraceEntity trace, LocalDate date, String symbol) {
        List<PaperTradeEntity> trades = paperTradeRepository.findByEntryDateAndSymbol(date, symbol);
        Optional<PaperTradeEntity> trade = trades.stream().findFirst();
        trade.ifPresentOrElse(t -> {
            trace.setBuyStatus("HIT");
            trace.setBuyReason(t.getStatus());
            trace.setBuyTradeId(t.getId());
            trace.setBuyTradeRef(t.getTradeId());
            trace.setExitStatus(t.getExitDate() == null ? "OPEN" : "CLOSED");
            trace.setExitReason(t.getExitReason());
            trace.setExitRefId(t.getId());
            trace.setFinalOutcome1d(t.getReturn1d());
            trace.setFinalOutcome5d(t.getReturn5d());
            trace.setFinalOutcome10d(t.getReturn10d());
            trace.setMaxDrawdown10d(t.getMaePct());
        }, () -> trace.setBuyStatus("MISS"));
    }

    private void enrichForwardTracking(TradingFunnelTraceEntity trace, LocalDate date, String symbol) {
        try {
            forwardTrackingRepository.findByTradingDateAndStockId(date, symbol).stream().findFirst().ifPresent(f -> {
                if (trace.getFinalOutcome1d() == null) trace.setFinalOutcome1d(f.getT1CloseReturnPct());
                if (trace.getFinalOutcome5d() == null) trace.setFinalOutcome5d(f.getT5CloseReturnPct());
                if (trace.getFinalOutcome10d() == null) trace.setFinalOutcome10d(f.getT10CloseReturnPct());
                if (trace.getMaxDrawdown10d() == null) trace.setMaxDrawdown10d(f.getMaxDrawdownPct());
            });
        } catch (Exception ex) {
            log.warn("Funnel forward returns lookup failed for {} {}: {}", date, symbol, ex.toString());
        }
    }

    private void resolveBlockedStage(TradingFunnelTraceEntity trace) {
        if (trace.getCandidateStatus() == null || "MISS".equals(trace.getCandidateStatus())) {
            trace.setBlockedStage(TradingFunnelBlockedStage.CANDIDATE);
            trace.setBlockedReason("not found in candidate_stock for date");
        } else if ("MISS".equals(trace.getRankingStatus())) {
            trace.setBlockedStage(TradingFunnelBlockedStage.RANKING);
            trace.setBlockedReason("not found in stock_ranking_snapshot for date");
        } else if ("BLOCKED".equals(trace.getRiskStatus())) {
            trace.setBlockedStage(TradingFunnelBlockedStage.RISK);
            trace.setBlockedReason(trace.getRiskReason());
        } else if ("MISS".equals(trace.getBuyStatus())) {
            trace.setBlockedStage(TradingFunnelBlockedStage.BUY);
            trace.setBlockedReason("no paper trade opened for date");
        } else {
            trace.setBlockedStage(TradingFunnelBlockedStage.NONE);
            trace.setBlockedReason(null);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
