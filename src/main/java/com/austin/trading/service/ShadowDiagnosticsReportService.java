package com.austin.trading.service;

import com.austin.trading.dto.internal.RankingTopNShadowResultDto;
import com.austin.trading.dto.internal.ThemeAdmissionShadowDecisionDto;
import com.austin.trading.dto.internal.TradingFunnelTraceDto;
import com.austin.trading.entity.RankingTopNShadowResultEntity;
import com.austin.trading.entity.ThemeAdmissionShadowDecisionEntity;
import com.austin.trading.entity.TradingFunnelTraceEntity;
import com.austin.trading.repository.RankingTopNShadowResultRepository;
import com.austin.trading.repository.ThemeAdmissionShadowDecisionRepository;
import com.austin.trading.repository.TradingFunnelTraceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Read-only diagnostics over existing shadow tables. Never rebuilds or mutates trading state. */
@Service
public class ShadowDiagnosticsReportService {

    private final TradingFunnelTraceRepository funnelTraceRepository;
    private final ThemeAdmissionShadowDecisionRepository themeDecisionRepository;
    private final RankingTopNShadowResultRepository rankingTopNRepository;

    public ShadowDiagnosticsReportService(TradingFunnelTraceRepository funnelTraceRepository,
                                          ThemeAdmissionShadowDecisionRepository themeDecisionRepository,
                                          RankingTopNShadowResultRepository rankingTopNRepository) {
        this.funnelTraceRepository = funnelTraceRepository;
        this.themeDecisionRepository = themeDecisionRepository;
        this.rankingTopNRepository = rankingTopNRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> conversion(int days) {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(Math.max(days, 1) - 1L);
        List<TradingFunnelTraceEntity> rows = funnelTraceRepository.findByTradingDateBetween(start, end);
        Map<String, Long> blockedByStage = rows.stream()
                .collect(Collectors.groupingBy(r -> r.getBlockedStage() == null ? "UNKNOWN" : r.getBlockedStage().name(),
                        LinkedHashMap::new, Collectors.counting()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startDate", start);
        result.put("endDate", end);
        result.put("days", Math.max(days, 1));
        result.put("totalSignals", rows.size());
        result.put("candidateHits", countStatus(rows, "candidate"));
        result.put("watchlistHits", countStatus(rows, "watchlist"));
        result.put("rankingHits", rows.stream().filter(r -> r.getRankingStatus() != null && !"MISS".equalsIgnoreCase(r.getRankingStatus())).count());
        result.put("riskApproved", rows.stream().filter(r -> "APPROVED".equalsIgnoreCase(r.getRiskStatus())).count());
        result.put("buyHits", countStatus(rows, "buy"));
        result.put("blockedByStage", blockedByStage);
        return result;
    }

    @Transactional(readOnly = true)
    public List<TradingFunnelTraceDto> symbolTrace(String symbol, int days) {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(Math.max(days, 1) - 1L);
        return funnelTraceRepository.findBySymbolAndTradingDateBetweenOrderByTradingDateDesc(symbol, start, end)
                .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<ThemeAdmissionShadowDecisionDto> themeAdmissionShadow(LocalDate date) {
        return themeDecisionRepository.findByTradingDate(date).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<RankingTopNShadowResultDto> rankingTopNShadow(int days) {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(Math.max(days, 1) - 1L);
        return rankingTopNRepository.findByTradingDateBetweenOrderByTradingDateDescRankingRankAsc(start, end)
                .stream().map(this::toDto).toList();
    }

    private long countStatus(List<TradingFunnelTraceEntity> rows, String stage) {
        return rows.stream().filter(r -> "HIT".equalsIgnoreCase(switch (stage) {
            case "candidate" -> r.getCandidateStatus();
            case "watchlist" -> r.getWatchlistStatus();

            case "buy" -> r.getBuyStatus();
            default -> null;
        })).count();
    }

    private TradingFunnelTraceDto toDto(TradingFunnelTraceEntity e) {
        return new TradingFunnelTraceDto(e.getId(), e.getTradingDate(), e.getSymbol(), e.getStockName(), e.getThemeTag(),
                e.getSignalId(), e.getSignalSource(), e.getSignalRole(), e.getSignalStrength(), e.getSignalChangePct(),
                e.getSignalNearLimit(), e.getSignalLimitRisk(), e.getCandidateStatus(), e.getCandidateReason(),
                e.getCandidateId(), e.getWatchlistStatus(), e.getWatchlistReason(), e.getWatchlistId(),
                e.getRankingStatus(), e.getRankingRank(), e.getRankingScore(), e.getRankingReason(), e.getRankingSnapshotId(),
                e.getSetupStatus(), e.getSetupReason(), e.getSetupDecisionId(), e.getRiskStatus(), e.getRiskReason(),
                e.getRiskDecisionId(), e.getPortfolioStatus(), e.getPortfolioReason(), e.getPositionId(), e.getBuyStatus(),
                e.getBuyReason(), e.getBuyTradeId(), e.getBuyTradeRef(), e.getExitStatus(), e.getExitReason(), e.getExitRefId(),
                e.getFinalOutcome1d(), e.getFinalOutcome5d(), e.getFinalOutcome10d(), e.getMaxDrawdown10d(),
                e.getBlockedStage(), e.getBlockedReason(), e.getTraceSource(), e.getTraceStatus(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private ThemeAdmissionShadowDecisionDto toDto(ThemeAdmissionShadowDecisionEntity e) {
        return new ThemeAdmissionShadowDecisionDto(e.getId(), e.getTradingDate(), e.getSymbol(), e.getStockName(), e.getThemeTag(),
                e.getSignalId(), e.getSignalRole(), e.getCurrentAction(), e.getCurrentReason(), e.getShadowAction(), e.getShadowReason(),
                e.getWouldWriteCandidate(), e.getWouldWriteWatchlist(), e.getWouldCreatePullbackPlan(), e.getWouldBypassTopN(),
                e.getBlockedByCurrentStage(), e.getDeltaStage(), e.getAdmissionScore(), e.getThemeStrength(), e.getSignalStrength(),
                e.getRankInTheme(), e.getNearLimit(), e.getLimitRisk(), e.getSourceTraceId(), e.getEvidenceJson(),
                e.getTraceSource(), e.getTraceStatus(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private RankingTopNShadowResultDto toDto(RankingTopNShadowResultEntity e) {
        return new RankingTopNShadowResultDto(e.getId(), e.getTradingDate(), e.getRunId(), e.getSnapshotId(), e.getSymbol(),
                e.getStockName(), e.getThemeTag(), e.getBucket(), e.getCurrentSelected(), e.getWouldSelectTop5(),
                e.getWouldSelectTop10(), e.getWouldSelectTop20(), e.getRankingRank(), e.getRankingScore(), e.getRankingStatus(),
                e.getRankingReason(), e.getCandidateId(), e.getSourceTraceId(), e.getActualReturn1d(), e.getActualReturn5d(),
                e.getActualReturn10d(), e.getMaxDrawdown10d(), e.getMissedByTop3(), e.getScoreBreakdownJson(),
                e.getTraceSource(), e.getTraceStatus(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
