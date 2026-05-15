package com.austin.trading.service;

import com.austin.trading.dto.response.RiskRewardShadowGateResult;
import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.MarketIndexDailyEntity;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.entity.RrShadowValidationEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.MarketIndexDailyRepository;
import com.austin.trading.repository.PaperTradeRepository;
import com.austin.trading.repository.RrShadowValidationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RrShadowValidationService {

    private static final String SOURCE_PAPER_TRADE = "PAPER_TRADE";
    private static final String SOURCE_FORWARD_CANDIDATE = "FORWARD_CANDIDATE";
    private static final String FORWARD_PROXY_NOTE = "PROXY_RR_PLAN_FROM_FORWARD_CANDIDATE; SHADOW_ONLY";
    private static final int MIN_SAMPLE_THRESHOLD = 50;
    private static final BigDecimal MIN_COVERAGE_PCT = new BigDecimal("80.00");
    private static final BigDecimal MAX_MISSED_WINNER_PCT = new BigDecimal("20.00");
    private static final BigDecimal STOP_TOO_WIDE_PCT = new BigDecimal("6.0");
    private static final BigDecimal TARGET_TOO_CLOSE_PCT = new BigDecimal("3.0");
    private static final BigDecimal MISSED_WINNER_PCT = new BigDecimal("3.0");
    private static final String BENCHMARK_SYMBOL = "t00";
    private static final int[] HORIZONS = {1, 3, 5, 10};

    private final PaperTradeRepository paperTradeRepository;
    private final CandidateForwardTrackingRepository forwardTrackingRepository;
    private final RrShadowValidationRepository validationRepository;
    private final RiskRewardShadowGateService shadowGateService;
    private final MarketIndexDailyRepository marketIndexRepository;

    @Autowired
    public RrShadowValidationService(PaperTradeRepository paperTradeRepository,
                                     CandidateForwardTrackingRepository forwardTrackingRepository,
                                     RrShadowValidationRepository validationRepository,
                                     RiskRewardShadowGateService shadowGateService,
                                     MarketIndexDailyRepository marketIndexRepository) {
        this.paperTradeRepository = paperTradeRepository;
        this.forwardTrackingRepository = forwardTrackingRepository;
        this.validationRepository = validationRepository;
        this.shadowGateService = shadowGateService;
        this.marketIndexRepository = marketIndexRepository;
    }

    public RrShadowValidationService(PaperTradeRepository paperTradeRepository,
                                     CandidateForwardTrackingRepository forwardTrackingRepository,
                                     RrShadowValidationRepository validationRepository,
                                     RiskRewardShadowGateService shadowGateService) {
        this(paperTradeRepository, forwardTrackingRepository, validationRepository, shadowGateService, null);
    }

    @Transactional
    public Map<String, Object> backfill(int days) {
        Window window = window(days);
        BackfillCounts counts = backfillPaperRows(window);

        Map<String, Object> out = baseBackfillResponse(window, counts);
        out.put("processedRows", counts.paperRowsProcessed());
        out.put("dataGapRows", counts.dataGapRows());
        out.put("safety", "SHADOW_ONLY: reads paper_trade/candidate_forward_tracking and writes rr_shadow_validation only");
        return out;
    }

    @Transactional
    public Map<String, Object> backfillExpanded(int days) {
        Window window = window(days);
        BackfillCounts paperCounts = backfillPaperRows(window);
        BackfillCounts forwardCounts = backfillForwardCandidateRows(window);
        BackfillCounts counts = paperCounts.plus(forwardCounts);

        Map<String, Object> out = baseBackfillResponse(window, counts);
        out.put("processedRows", counts.paperRowsProcessed() + counts.forwardRowsProcessed());
        out.put("dataGapRows", counts.dataGapRows());
        out.put("safety", "SHADOW_ONLY: expanded sample writes diagnosis-only rr_shadow_validation rows; no production BUY/SELL path");
        return out;
    }

    private BackfillCounts backfillPaperRows(Window window) {
        List<PaperTradeEntity> trades = paperTradeRepository.findByEntryDateBetweenOrderByEntryDateAscIdAsc(window.start(), window.end());
        Map<String, CandidateForwardTrackingEntity> forwardByTrade = forwardByTrade(window);

        int insertedOrUpdated = 0;
        int dataGapRows = 0;
        for (PaperTradeEntity trade : trades) {
            RrShadowValidationEntity row = trade.getId() == null
                    ? new RrShadowValidationEntity()
                    : validationRepository.findByPaperTradeId(trade.getId()).orElseGet(RrShadowValidationEntity::new);
            populate(row, trade, forwardByTrade.get(traceKey(trade.getEntryDate(), trade.getSymbol())));
            if (RiskRewardShadowGateService.DATA_GAP.equals(row.getShadowStatus()) || row.getDataGapReason() != null) {
                dataGapRows++;
            }
            validationRepository.save(row);
            insertedOrUpdated++;
        }

        return new BackfillCounts(trades.size(), 0, 0, insertedOrUpdated, dataGapRows);
    }

    private BackfillCounts backfillForwardCandidateRows(Window window) {
        List<CandidateForwardTrackingEntity> candidates =
                forwardTrackingRepository.findByTradingDateBetween(window.start(), window.end());

        int processed = 0;
        int skippedMissingPrice = 0;
        int insertedOrUpdated = 0;
        int dataGapRows = 0;
        for (CandidateForwardTrackingEntity candidate : candidates) {
            if (candidate.getEntryPriceAtDecision() == null || candidate.getEntryPriceAtDecision().signum() <= 0) {
                skippedMissingPrice++;
                continue;
            }
            if (candidate.getStockId() == null || candidate.getStockId().isBlank()) {
                skippedMissingPrice++;
                continue;
            }
            RrShadowValidationEntity row = candidate.getId() == null
                    ? new RrShadowValidationEntity()
                    : validationRepository.findBySourceForwardTrackingId(candidate.getId()).orElseGet(RrShadowValidationEntity::new);
            populate(row, candidate);
            if (RiskRewardShadowGateService.DATA_GAP.equals(row.getShadowStatus()) || row.getDataGapReason() != null) {
                dataGapRows++;
            }
            validationRepository.save(row);
            processed++;
            insertedOrUpdated++;
        }
        return new BackfillCounts(0, processed, skippedMissingPrice, insertedOrUpdated, dataGapRows);
    }

    private Map<String, Object> baseBackfillResponse(Window window, BackfillCounts counts) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", window.days());
        out.put("from", window.start());
        out.put("to", window.end());
        out.put("paperRowsProcessed", counts.paperRowsProcessed());
        out.put("forwardRowsProcessed", counts.forwardRowsProcessed());
        out.put("forwardRowsSkippedMissingPrice", counts.forwardRowsSkippedMissingPrice());
        out.put("upsertedRows", counts.upsertedRows());
        out.put("sourceTypes", counts.sourceTypes());
        return out;
    }

    @Transactional(readOnly = true)
    public Summary summary(int days) {
        Window window = window(days);
        List<RrShadowValidationEntity> rows =
                validationRepository.findByTradingDateBetweenOrderByTradingDateAscIdAsc(window.start(), window.end());
        return summarize(window, rows);
    }

    @Transactional(readOnly = true)
    public boolean hasRows(int days) {
        Window window = window(days);
        return validationRepository.countByTradingDateBetween(window.start(), window.end()) > 0;
    }

    private void populate(RrShadowValidationEntity row,
                          PaperTradeEntity trade,
                          CandidateForwardTrackingEntity forward) {
        RiskRewardShadowGateResult gate = shadowGateService.evaluate(new RiskRewardShadowGateService.PriceSnapshot(
                trade.getSymbol(),
                trade.getStrategyType(),
                trade.getEntryPrice(),
                trade.getStopLossPrice(),
                trade.getTarget1Price(),
                trade.getTarget2Price()
        ));
        row.setSourceType(SOURCE_PAPER_TRADE);
        row.setPaperTradeId(trade.getId());
        row.setSourceForwardTrackingId(null);
        row.setTradingDate(trade.getEntryDate());
        row.setSymbol(trade.getSymbol());
        row.setStrategyType(trade.getStrategyType());
        row.setEntryPrice(trade.getEntryPrice());
        row.setStopLossPrice(trade.getStopLossPrice());
        row.setTarget1Price(trade.getTarget1Price());
        row.setTarget2Price(trade.getTarget2Price());
        row.setRrRatio(gate.rrValue());
        row.setShadowStatus(gate.shadowStatus());
        row.setRootCauseBucket(rootCauseBucket(trade.getEntryPrice(), trade.getStopLossPrice(), trade.getTarget1Price(), gate));
        row.setFinalDecision(null);
        row.setFinalScore(null);
        row.setGrade(null);
        row.setThemeTag(trade.getThemeTag());
        row.setGateName(null);
        row.setValidationNote("PAPER_TRADE_RR_PLAN; SHADOW_ONLY");
        row.setT1ReturnPct(firstNonNull(trade.getReturn1d(), forward == null ? null : forward.getT1CloseReturnPct()));
        row.setT3ReturnPct(firstNonNull(trade.getReturn3d(), forward == null ? null : forward.getT3CloseReturnPct()));
        row.setT5ReturnPct(firstNonNull(trade.getReturn5d(), forward == null ? null : forward.getT5CloseReturnPct()));
        row.setT10ReturnPct(firstNonNull(trade.getReturn10d(), forward == null ? null : forward.getT10CloseReturnPct()));
        fillReturnsFromMarketIndex(row, trade.getEntryDate(), trade.getSymbol(), trade.getEntryPrice());
        BigDecimal bestReturn = bestAvailableReturn(row);
        row.setAvoidedLoserFlag(RiskRewardShadowGateService.FAIL.equals(row.getShadowStatus())
                && bestReturn != null && bestReturn.compareTo(BigDecimal.ZERO) < 0);
        row.setMissedWinnerFlag(RiskRewardShadowGateService.FAIL.equals(row.getShadowStatus())
                && bestReturn != null && bestReturn.compareTo(MISSED_WINNER_PCT) > 0);
        row.setDataGapReason(dataGapReason(row, gate));
    }

    private void populate(RrShadowValidationEntity row, CandidateForwardTrackingEntity candidate) {
        ProxyPlan plan = proxyPlan(candidate.getEntryPriceAtDecision(), candidate.getPrimaryStrategy());
        String strategyType = candidate.getPrimaryStrategy() == null || candidate.getPrimaryStrategy().isBlank()
                ? "UNKNOWN"
                : candidate.getPrimaryStrategy();
        RiskRewardShadowGateResult gate = shadowGateService.evaluate(new RiskRewardShadowGateService.PriceSnapshot(
                candidate.getStockId(),
                strategyType,
                candidate.getEntryPriceAtDecision(),
                plan.stopLossPrice(),
                plan.target1Price(),
                plan.target2Price()
        ));
        row.setSourceType(SOURCE_FORWARD_CANDIDATE);
        row.setPaperTradeId(null);
        row.setSourceForwardTrackingId(candidate.getId());
        row.setTradingDate(candidate.getTradingDate());
        row.setSymbol(candidate.getStockId());
        row.setStrategyType(strategyType);
        row.setEntryPrice(candidate.getEntryPriceAtDecision());
        row.setStopLossPrice(plan.stopLossPrice());
        row.setTarget1Price(plan.target1Price());
        row.setTarget2Price(plan.target2Price());
        row.setRrRatio(gate.rrValue());
        row.setShadowStatus(gate.shadowStatus());
        row.setRootCauseBucket(rootCauseBucket(candidate.getEntryPriceAtDecision(), plan.stopLossPrice(), plan.target1Price(), gate));
        row.setFinalDecision(candidate.getFinalDecision());
        row.setFinalScore(candidate.getFinalScore());
        row.setGrade(candidate.getGrade());
        row.setThemeTag(candidate.getThemeTag());
        row.setGateName(candidate.getGateName());
        row.setValidationNote(FORWARD_PROXY_NOTE);
        row.setT1ReturnPct(candidate.getT1CloseReturnPct());
        row.setT3ReturnPct(candidate.getT3CloseReturnPct());
        row.setT5ReturnPct(candidate.getT5CloseReturnPct());
        row.setT10ReturnPct(candidate.getT10CloseReturnPct());
        fillReturnsFromMarketIndex(row, candidate.getTradingDate(), candidate.getStockId(), candidate.getEntryPriceAtDecision());
        BigDecimal bestReturn = bestAvailableReturn(row);
        row.setAvoidedLoserFlag(RiskRewardShadowGateService.FAIL.equals(row.getShadowStatus())
                && bestReturn != null && bestReturn.compareTo(BigDecimal.ZERO) < 0);
        row.setMissedWinnerFlag(RiskRewardShadowGateService.FAIL.equals(row.getShadowStatus())
                && bestReturn != null && bestReturn.compareTo(MISSED_WINNER_PCT) > 0);
        row.setDataGapReason(dataGapReason(row, gate));
    }

    private String rootCauseBucket(BigDecimal entryPrice,
                                   BigDecimal stopLossPrice,
                                   BigDecimal target1Price,
                                   RiskRewardShadowGateResult gate) {
        if (RiskRewardShadowGateService.DATA_GAP.equals(gate.shadowStatus())) return "DATA_GAP";
        BigDecimal stopPct = pctDistance(entryPrice, stopLossPrice);
        if (stopPct != null && stopPct.compareTo(STOP_TOO_WIDE_PCT) > 0) return "STOP_TOO_WIDE";
        BigDecimal targetPct = pctGain(entryPrice, target1Price);
        if (targetPct != null && targetPct.compareTo(TARGET_TOO_CLOSE_PCT) < 0) return "TARGET_TOO_CLOSE";
        if (RiskRewardShadowGateService.FAIL.equals(gate.shadowStatus())) return "OTHER_LOW_RR";
        return "PASS";
    }

    private String dataGapReason(RrShadowValidationEntity row, RiskRewardShadowGateResult gate) {
        List<String> gaps = new ArrayList<>();
        if (RiskRewardShadowGateService.DATA_GAP.equals(row.getShadowStatus())) {
            gaps.add(gate.reason());
        }
        if (row.getT1ReturnPct() == null) gaps.add("DATA_GAP: missing T1 forward return");
        if (row.getT3ReturnPct() == null) gaps.add("DATA_GAP: missing T3 forward return");
        if (row.getT5ReturnPct() == null) gaps.add("DATA_GAP: missing T5 forward return");
        if (row.getT10ReturnPct() == null) gaps.add("DATA_GAP: missing T10 forward return");
        return gaps.isEmpty() ? null : String.join("; ", gaps);
    }

    private void fillReturnsFromMarketIndex(RrShadowValidationEntity row,
                                            LocalDate entryDate,
                                            String symbol,
                                            BigDecimal entryPrice) {
        if (marketIndexRepository == null
                || entryDate == null
                || symbol == null
                || symbol.isBlank()
                || entryPrice == null
                || entryPrice.signum() <= 0) {
            return;
        }
        boolean needsAny = row.getT1ReturnPct() == null
                || row.getT3ReturnPct() == null
                || row.getT5ReturnPct() == null
                || row.getT10ReturnPct() == null;
        if (!needsAny) return;

        List<MarketIndexDailyEntity> futureBars = marketIndexRepository
                .findBySymbolAndTradingDateBetweenOrderByTradingDateAsc(
                        symbol, entryDate.plusDays(1), entryDate.plusDays(30));
        List<MarketIndexDailyEntity> usableBars = futureBars.stream()
                .filter(b -> b.getClosePrice() != null)
                .limit(10)
                .toList();
        for (int horizon : HORIZONS) {
            if (usableBars.size() < horizon || returnAt(row, horizon) != null) continue;
            BigDecimal pct = returnPct(entryPrice, usableBars.get(horizon - 1).getClosePrice());
            writeReturn(row, horizon, pct);
        }
    }

    private Summary summarize(Window window, List<RrShadowValidationEntity> rows) {
        List<RrShadowValidationEntity> blocked = rows.stream()
                .filter(r -> RiskRewardShadowGateService.FAIL.equals(r.getShadowStatus()))
                .toList();
        int dataGapRows = (int) rows.stream()
                .filter(r -> RiskRewardShadowGateService.DATA_GAP.equals(r.getShadowStatus()) || r.getDataGapReason() != null)
                .count();
        Map<String, Integer> returnGaps = new LinkedHashMap<>();
        returnGaps.put("T1", missing(blocked, 1));
        returnGaps.put("T3", missing(blocked, 3));
        returnGaps.put("T5", missing(blocked, 5));
        returnGaps.put("T10", missing(blocked, 10));
        BigDecimal blockedReturnCoveragePct = coveragePct(blocked);
        BigDecimal missedWinnerPct = pct((int) blocked.stream().filter(RrShadowValidationEntity::isMissedWinnerFlag).count(), blocked.size());
        return new Summary(
                window.days(),
                window.start(),
                window.end(),
                rows.size(),
                blocked.size(),
                dataGapRows,
                blocked.size(),
                pct(blocked.size(), rows.size()),
                avg(blocked.stream().map(RrShadowValidationEntity::getT1ReturnPct).toList()),
                avg(blocked.stream().map(RrShadowValidationEntity::getT3ReturnPct).toList()),
                avg(blocked.stream().map(RrShadowValidationEntity::getT5ReturnPct).toList()),
                avg(blocked.stream().map(RrShadowValidationEntity::getT10ReturnPct).toList()),
                returnGaps,
                (int) blocked.stream().filter(RrShadowValidationEntity::isAvoidedLoserFlag).count(),
                (int) blocked.stream().filter(RrShadowValidationEntity::isMissedWinnerFlag).count(),
                topBuckets(rows),
                topBuckets(rows),
                rows.stream().map(RrShadowValidationEntity::getSymbol).filter(s -> s != null && !s.isBlank()).distinct().limit(10).toList(),
                blockedReturnCoveragePct,
                coverageGapDetails(rows, blocked),
                grouped(rows, RrShadowValidationEntity::getSourceType, 20),
                grouped(rows, RrShadowValidationEntity::getStrategyType, 20),
                grouped(rows, RrShadowValidationEntity::getFinalDecision, 20),
                grouped(rows, RrShadowValidationEntity::getThemeTag, 10),
                promotionReadiness(rows.size(), blockedReturnCoveragePct, missedWinnerPct),
                rows.size() < MIN_SAMPLE_THRESHOLD
                        ? "INSUFFICIENT_SAMPLE: totalRows " + rows.size() + " < " + MIN_SAMPLE_THRESHOLD
                        : null
        );
    }

    private CoverageGapDetails coverageGapDetails(List<RrShadowValidationEntity> rows,
                                                  List<RrShadowValidationEntity> blocked) {
        LocalDate oldest = rows.stream().map(RrShadowValidationEntity::getTradingDate)
                .filter(d -> d != null).min(LocalDate::compareTo).orElse(null);
        LocalDate newest = rows.stream().map(RrShadowValidationEntity::getTradingDate)
                .filter(d -> d != null).max(LocalDate::compareTo).orElse(null);

        List<String> missingSymbols = blocked.stream()
                .filter(r -> bestAvailableReturn(r) == null)
                .map(r -> r.getSymbol() + "@" + r.getTradingDate())
                .distinct()
                .limit(30)
                .toList();
        Map<String, List<String>> missingHorizons = new LinkedHashMap<>();
        for (RrShadowValidationEntity row : blocked) {
            List<String> horizons = new ArrayList<>();
            for (int horizon : HORIZONS) {
                if (returnAt(row, horizon) == null) horizons.add("T" + horizon);
            }
            if (!horizons.isEmpty()) {
                missingHorizons.put(row.getSymbol() + "@" + row.getTradingDate(), horizons);
            }
            if (missingHorizons.size() >= 30) break;
        }

        return new CoverageGapDetails(
                missingSymbols,
                missingBenchmark(blocked),
                missingHorizons,
                oldest,
                newest
        );
    }

    private List<String> missingBenchmark(List<RrShadowValidationEntity> blocked) {
        if (marketIndexRepository == null) return List.of();
        return blocked.stream()
                .filter(r -> r.getTradingDate() != null)
                .filter(r -> marketIndexRepository.findTradingDatesAfter(
                        BENCHMARK_SYMBOL, r.getTradingDate(), PageRequest.of(0, 10)).isEmpty())
                .map(r -> BENCHMARK_SYMBOL + "@" + r.getTradingDate())
                .distinct()
                .limit(30)
                .toList();
    }

    private Map<String, Long> topBuckets(List<RrShadowValidationEntity> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        rows.stream()
                .filter(r -> r.getRootCauseBucket() != null && !r.getRootCauseBucket().isBlank())
                .collect(java.util.stream.Collectors.groupingBy(RrShadowValidationEntity::getRootCauseBucket,
                        java.util.LinkedHashMap::new, java.util.stream.Collectors.counting()))
                .entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(5)
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    private Map<String, GroupStats> grouped(List<RrShadowValidationEntity> rows,
                                            java.util.function.Function<RrShadowValidationEntity, String> classifier,
                                            int limit) {
        Map<String, GroupStats> groups = new LinkedHashMap<>();
        rows.stream()
                .collect(java.util.stream.Collectors.groupingBy(r -> normalizedGroup(classifier.apply(r)),
                        java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()))
                .entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .limit(limit)
                .forEach(e -> groups.put(e.getKey(), groupStats(e.getValue())));
        return groups;
    }

    private GroupStats groupStats(List<RrShadowValidationEntity> rows) {
        List<RrShadowValidationEntity> blocked = rows.stream()
                .filter(r -> RiskRewardShadowGateService.FAIL.equals(r.getShadowStatus()))
                .toList();
        return new GroupStats(
                rows.size(),
                blocked.size(),
                pct(blocked.size(), rows.size()),
                (int) blocked.stream().filter(RrShadowValidationEntity::isAvoidedLoserFlag).count(),
                (int) blocked.stream().filter(RrShadowValidationEntity::isMissedWinnerFlag).count(),
                avg(blocked.stream().map(RrShadowValidationEntity::getT5ReturnPct).toList())
        );
    }

    private String normalizedGroup(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    private PromotionReadiness promotionReadiness(int totalRows,
                                                  BigDecimal blockedReturnCoveragePct,
                                                  BigDecimal missedWinnerPct) {
        List<String> reasons = new ArrayList<>();
        if (totalRows < MIN_SAMPLE_THRESHOLD) {
            reasons.add("INSUFFICIENT_SAMPLE: totalRows " + totalRows + " < " + MIN_SAMPLE_THRESHOLD);
        }
        if (blockedReturnCoveragePct.compareTo(MIN_COVERAGE_PCT) < 0) {
            reasons.add("NEED_MORE_FORWARD_COVERAGE: blockedReturnCoveragePct "
                    + blockedReturnCoveragePct + " < " + MIN_COVERAGE_PCT);
        }
        if (missedWinnerPct.compareTo(MAX_MISSED_WINNER_PCT) > 0) {
            reasons.add("MISSED_WINNER_TOO_HIGH: missedWinnerPct "
                    + missedWinnerPct + " > " + MAX_MISSED_WINNER_PCT);
        }

        String status;
        if (totalRows < MIN_SAMPLE_THRESHOLD) {
            status = "INSUFFICIENT_SAMPLE";
        } else if (blockedReturnCoveragePct.compareTo(MIN_COVERAGE_PCT) < 0) {
            status = "NEED_MORE_FORWARD_COVERAGE";
        } else if (missedWinnerPct.compareTo(MAX_MISSED_WINNER_PCT) > 0) {
            status = "SHADOW_OK_BUT_NOT_PRODUCTION";
        } else {
            status = "CANDIDATE_FOR_SOFT_ADVISORY";
            reasons.add("SHADOW_ONLY: eligible only for soft advisory review, not production gate");
        }

        return new PromotionReadiness(
                status,
                reasons,
                MIN_SAMPLE_THRESHOLD,
                MIN_COVERAGE_PCT,
                MAX_MISSED_WINNER_PCT,
                missedWinnerPct
        );
    }

    private BigDecimal coveragePct(List<RrShadowValidationEntity> blocked) {
        if (blocked.isEmpty()) return BigDecimal.ZERO;
        long covered = blocked.stream().filter(r -> bestAvailableReturn(r) != null).count();
        return pct((int) covered, blocked.size());
    }

    private int missing(List<RrShadowValidationEntity> rows, int horizon) {
        return (int) rows.stream().filter(r -> returnAt(r, horizon) == null).count();
    }

    private Map<String, CandidateForwardTrackingEntity> forwardByTrade(Window window) {
        Map<String, CandidateForwardTrackingEntity> out = new LinkedHashMap<>();
        for (CandidateForwardTrackingEntity row : forwardTrackingRepository.findByTradingDateBetween(window.start(), window.end())) {
            out.putIfAbsent(traceKey(row.getTradingDate(), row.getStockId()), row);
        }
        return out;
    }

    private BigDecimal bestAvailableReturn(RrShadowValidationEntity row) {
        if (row.getT10ReturnPct() != null) return row.getT10ReturnPct();
        if (row.getT5ReturnPct() != null) return row.getT5ReturnPct();
        if (row.getT3ReturnPct() != null) return row.getT3ReturnPct();
        return row.getT1ReturnPct();
    }

    private BigDecimal returnAt(RrShadowValidationEntity row, int horizon) {
        return switch (horizon) {
            case 1 -> row.getT1ReturnPct();
            case 3 -> row.getT3ReturnPct();
            case 5 -> row.getT5ReturnPct();
            case 10 -> row.getT10ReturnPct();
            default -> null;
        };
    }

    private BigDecimal firstNonNull(BigDecimal first, BigDecimal second) {
        return first != null ? first : second;
    }

    private BigDecimal pctDistance(BigDecimal entry, BigDecimal stop) {
        if (entry == null || stop == null || entry.signum() <= 0) return null;
        return entry.subtract(stop).abs().divide(entry, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    }

    private BigDecimal pctGain(BigDecimal entry, BigDecimal target) {
        if (entry == null || target == null || entry.signum() <= 0) return null;
        return target.subtract(entry).divide(entry, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    }

    private BigDecimal avg(List<BigDecimal> values) {
        List<BigDecimal> usable = values.stream().filter(v -> v != null).toList();
        if (usable.isEmpty()) return null;
        BigDecimal sum = usable.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(usable.size()), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal pct(int count, int total) {
        if (total <= 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(count).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal returnPct(BigDecimal entry, BigDecimal close) {
        if (entry == null || close == null || entry.signum() <= 0) return null;
        return close.subtract(entry)
                .divide(entry, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private ProxyPlan proxyPlan(BigDecimal entry, String strategyType) {
        String normalized = strategyType == null ? "" : strategyType.toUpperCase();
        BigDecimal stopMultiplier;
        BigDecimal target1Multiplier;
        BigDecimal target2Multiplier;
        if (normalized.contains("MOMENTUM") || normalized.contains("CONTINUATION") || normalized.contains("BREAKOUT")) {
            stopMultiplier = new BigDecimal("0.94");
            target1Multiplier = new BigDecimal("1.08");
            target2Multiplier = new BigDecimal("1.12");
        } else if (normalized.contains("PULLBACK") || normalized.contains("SETUP")) {
            stopMultiplier = new BigDecimal("0.96");
            target1Multiplier = new BigDecimal("1.06");
            target2Multiplier = new BigDecimal("1.10");
        } else {
            stopMultiplier = new BigDecimal("0.95");
            target1Multiplier = new BigDecimal("1.07");
            target2Multiplier = new BigDecimal("1.10");
        }
        return new ProxyPlan(
                entry.multiply(stopMultiplier).setScale(4, RoundingMode.HALF_UP),
                entry.multiply(target1Multiplier).setScale(4, RoundingMode.HALF_UP),
                entry.multiply(target2Multiplier).setScale(4, RoundingMode.HALF_UP)
        );
    }

    private void writeReturn(RrShadowValidationEntity row, int horizon, BigDecimal value) {
        if (value == null) return;
        switch (horizon) {
            case 1 -> row.setT1ReturnPct(value);
            case 3 -> row.setT3ReturnPct(value);
            case 5 -> row.setT5ReturnPct(value);
            case 10 -> row.setT10ReturnPct(value);
            default -> { }
        }
    }

    private Window window(int days) {
        int window = days > 0 ? days : 60;
        LocalDate end = LocalDate.now();
        return new Window(window, end.minusDays(window), end);
    }

    private String traceKey(LocalDate date, String symbol) {
        return (date == null ? "" : date.toString()) + "|" + (symbol == null ? "" : symbol);
    }

    private record Window(int days, LocalDate start, LocalDate end) {}

    private record ProxyPlan(BigDecimal stopLossPrice, BigDecimal target1Price, BigDecimal target2Price) {}

    private record BackfillCounts(int paperRowsProcessed,
                                  int forwardRowsProcessed,
                                  int forwardRowsSkippedMissingPrice,
                                  int upsertedRows,
                                  int dataGapRows) {
        BackfillCounts plus(BackfillCounts other) {
            return new BackfillCounts(
                    paperRowsProcessed + other.paperRowsProcessed,
                    forwardRowsProcessed + other.forwardRowsProcessed,
                    forwardRowsSkippedMissingPrice + other.forwardRowsSkippedMissingPrice,
                    upsertedRows + other.upsertedRows,
                    dataGapRows + other.dataGapRows
            );
        }

        List<String> sourceTypes() {
            List<String> types = new ArrayList<>();
            if (paperRowsProcessed > 0) types.add(SOURCE_PAPER_TRADE);
            if (forwardRowsProcessed > 0) types.add(SOURCE_FORWARD_CANDIDATE);
            return types;
        }
    }

    public record Summary(
            int days,
            LocalDate from,
            LocalDate to,
            int totalRows,
            int failedGateRows,
            int dataGapRows,
            int wouldBlockCount,
            BigDecimal wouldBlockPct,
            BigDecimal blockedAvgReturnT1,
            BigDecimal blockedAvgReturnT3,
            BigDecimal blockedAvgReturnT5,
            BigDecimal blockedAvgReturnT10,
            Map<String, Integer> dataGaps,
            int avoidedLoserCount,
            int missedWinnerCount,
            Map<String, Long> topRootCauseBuckets,
            Map<String, Long> byRootCauseBucket,
            List<String> sampleSymbols,
            BigDecimal blockedReturnCoveragePct,
            CoverageGapDetails coverageGaps,
            Map<String, GroupStats> bySourceType,
            Map<String, GroupStats> byStrategyType,
            Map<String, GroupStats> byFinalDecision,
            Map<String, GroupStats> byThemeTag,
            PromotionReadiness promotionReadiness,
            String sampleSizeWarning
    ) {}

    public record GroupStats(
            int totalRows,
            int wouldBlockCount,
            BigDecimal wouldBlockPct,
            int avoidedLoserCount,
            int missedWinnerCount,
            BigDecimal blockedAvgReturnT5
    ) {}

    public record PromotionReadiness(
            String status,
            List<String> reasons,
            int minSampleThreshold,
            BigDecimal minCoveragePct,
            BigDecimal maxMissedWinnerPct,
            BigDecimal missedWinnerPct
    ) {}

    public record CoverageGapDetails(
            List<String> missingSymbols,
            List<String> missingBenchmark,
            Map<String, List<String>> missingHorizons,
            LocalDate oldestEntryDate,
            LocalDate newestEntryDate
    ) {}
}
