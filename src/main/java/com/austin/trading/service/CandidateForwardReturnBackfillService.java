package com.austin.trading.service;

import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.MarketIndexDailyEntity;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.MarketIndexDailyRepository;
import com.austin.trading.repository.PaperTradeRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CandidateForwardReturnBackfillService {

    private static final String BENCHMARK_SYMBOL = "t00";
    private static final int[] HORIZONS = {1, 3, 5, 10};

    private final CandidateForwardTrackingRepository candidateRepository;
    private final PaperTradeRepository paperTradeRepository;
    private final MarketIndexDailyRepository marketIndexRepository;

    public CandidateForwardReturnBackfillService(CandidateForwardTrackingRepository candidateRepository,
                                                 PaperTradeRepository paperTradeRepository,
                                                 MarketIndexDailyRepository marketIndexRepository) {
        this.candidateRepository = candidateRepository;
        this.paperTradeRepository = paperTradeRepository;
        this.marketIndexRepository = marketIndexRepository;
    }

    @Transactional
    public Map<String, Object> backfillReturns(int days) {
        int window = days > 0 ? days : 60;
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(window);

        List<CandidateForwardTrackingEntity> rows = candidateRepository.findByTradingDateBetween(start, end);
        int createdFromPaper = 0;
        if (rows.isEmpty()) {
            createdFromPaper = createFromPaperTrades(start, end);
            rows = candidateRepository.findByTradingDateBetween(start, end);
        }

        int processed = 0;
        int updated = 0;
        int dataGap = 0;
        List<Map<String, Object>> dataGaps = new ArrayList<>();
        Map<String, Integer> benchmarkHorizons = new LinkedHashMap<>();

        for (CandidateForwardTrackingEntity row : rows) {
            processed++;
            FillResult result = fillRow(row, end);
            if (result.hasDataGap()) {
                dataGap++;
                if (dataGaps.size() < 10) dataGaps.add(result.sample(row));
            }
            if (result.benchmarkHorizon() != null) {
                benchmarkHorizons.merge("T" + result.benchmarkHorizon(), 1, Integer::sum);
            }
            if (result.changed()) {
                candidateRepository.save(row);
                updated++;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("processedRows", processed);
        out.put("updatedRows", updated);
        out.put("dataGapRows", dataGap);
        out.put("createdFromPaperRows", createdFromPaper);
        out.put("start", start);
        out.put("end", end);
        out.put("benchmarkHorizon", benchmarkHorizons.isEmpty() ? "DATA_GAP" : benchmarkHorizons);
        out.put("dataGaps", dataGaps);
        return out;
    }

    private int createFromPaperTrades(LocalDate start, LocalDate end) {
        List<PaperTradeEntity> trades = paperTradeRepository.findByEntryDateBetweenOrderByEntryDateAscIdAsc(start, end);
        int created = 0;
        for (PaperTradeEntity t : trades) {
            String decision = "PAPER_" + (t.isShadow() ? "SHADOW" : "ENTER");
            if (candidateRepository.findByTradingDateAndStockIdAndFinalDecision(
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
            row.setThemeTag(t.getThemeTag());
            candidateRepository.save(row);
            created++;
        }
        return created;
    }

    private FillResult fillRow(CandidateForwardTrackingEntity row, LocalDate asOf) {
        List<String> gaps = validateBase(row);
        if (!gaps.isEmpty()) return new FillResult(false, gaps, null);

        List<LocalDate> futureDates = marketIndexRepository.findTradingDatesAfter(
                BENCHMARK_SYMBOL, row.getTradingDate(), PageRequest.of(0, 10));
        if (futureDates.isEmpty()) {
            gaps.add("DATA_GAP: no future TAIEX trading days after " + row.getTradingDate());
            return new FillResult(false, gaps, null);
        }
        for (int horizon : HORIZONS) {
            if (futureDates.size() < horizon) {
                gaps.add("DATA_GAP: T" + horizon + " missing benchmark calendar after " + row.getTradingDate());
            } else if (futureDates.get(horizon - 1).isAfter(asOf)) {
                gaps.add("DATA_GAP: T" + horizon + " horizon " + futureDates.get(horizon - 1)
                        + " is after run date " + asOf);
            }
        }
        int maxCalendarHorizon = maxAvailableHorizon(futureDates, asOf);
        if (maxCalendarHorizon == 0) {
            return new FillResult(false, gaps, null);
        }
        LocalDate horizonEnd = futureDates.get(maxCalendarHorizon - 1);

        List<MarketIndexDailyEntity> stockBars = marketIndexRepository
                .findBySymbolAndTradingDateBetweenOrderByTradingDateAsc(
                        row.getStockId(), row.getTradingDate(), horizonEnd);
        Map<LocalDate, MarketIndexDailyEntity> byDate = new LinkedHashMap<>();
        for (MarketIndexDailyEntity bar : stockBars) byDate.put(bar.getTradingDate(), bar);

        if (!byDate.containsKey(row.getTradingDate())) {
            gaps.add("DATA_GAP: missing entry-date stock daily bar " + row.getStockId() + " " + row.getTradingDate());
            return new FillResult(false, gaps, null);
        }

        boolean changed = false;
        int maxCompletedHorizon = 0;
        for (int horizon : HORIZONS) {
            if (futureDates.size() < horizon || futureDates.get(horizon - 1).isAfter(asOf)) continue;
            LocalDate horizonDate = futureDates.get(horizon - 1);
            MarketIndexDailyEntity closeBar = byDate.get(horizonDate);
            if (closeBar == null) {
                gaps.add("DATA_GAP: T" + horizon + " missing stock daily bar "
                        + row.getStockId() + " " + horizonDate);
                continue;
            }
            BigDecimal pct = returnPct(row.getEntryPriceAtDecision(), byDate.get(futureDates.get(horizon - 1)).getClosePrice());
            changed |= writeReturn(row, horizon, pct);
            maxCompletedHorizon = horizon;
        }
        if (maxCompletedHorizon == 0) return new FillResult(changed, gaps, null);

        Extremes extremes = extremes(row.getEntryPriceAtDecision(), stockBars);
        changed |= setIfDifferent(row.getMfePct(), extremes.mfe(), row::setMfePct);
        changed |= setIfDifferent(row.getMaePct(), extremes.mae(), row::setMaePct);
        changed |= setIfDifferent(row.getMaxDrawdownPct(), extremes.maxDrawdown(), row::setMaxDrawdownPct);

        BigDecimal benchmark = benchmarkReturn(row.getTradingDate(), futureDates, maxCompletedHorizon);
        if (benchmark == null) {
            gaps.add("DATA_GAP: T" + maxCompletedHorizon + " missing benchmark TAIEX bars for " + row.getTradingDate());
            return new FillResult(changed, gaps, null);
        }
        changed |= setIfDifferent(row.getBenchmarkReturnPct(), benchmark, row::setBenchmarkReturnPct);
        BigDecimal stockReturn = returnForHorizon(row, maxCompletedHorizon);
        BigDecimal relative = stockReturn.subtract(benchmark).setScale(4, RoundingMode.HALF_UP);
        changed |= setIfDifferent(row.getRelativeReturnPct(), relative, row::setRelativeReturnPct);

        return new FillResult(changed, gaps, maxCompletedHorizon);
    }

    private List<String> validateBase(CandidateForwardTrackingEntity row) {
        List<String> gaps = new ArrayList<>();
        if (row.getTradingDate() == null) gaps.add("DATA_GAP: missing tradingDate");
        if (row.getStockId() == null || row.getStockId().isBlank()) gaps.add("DATA_GAP: missing stockId");
        if (row.getEntryPriceAtDecision() == null || row.getEntryPriceAtDecision().signum() <= 0) {
            gaps.add("DATA_GAP: missing entryPriceAtDecision");
        }
        return gaps;
    }

    private int maxAvailableHorizon(List<LocalDate> futureDates, LocalDate asOf) {
        int max = 0;
        for (int horizon : HORIZONS) {
            if (futureDates.size() >= horizon && !futureDates.get(horizon - 1).isAfter(asOf)) max = horizon;
        }
        return max;
    }

    private BigDecimal benchmarkReturn(LocalDate entryDate, List<LocalDate> futureDates, int horizon) {
        var entry = marketIndexRepository.findBySymbolAndTradingDate(BENCHMARK_SYMBOL, entryDate)
                .map(MarketIndexDailyEntity::getClosePrice).orElse(null);
        var close = marketIndexRepository.findBySymbolAndTradingDate(BENCHMARK_SYMBOL, futureDates.get(horizon - 1))
                .map(MarketIndexDailyEntity::getClosePrice).orElse(null);
        if (entry == null || close == null || entry.signum() <= 0) return null;
        return returnPct(entry, close);
    }

    private BigDecimal returnForHorizon(CandidateForwardTrackingEntity row, int horizon) {
        return switch (horizon) {
            case 10 -> row.getT10CloseReturnPct();
            case 5 -> row.getT5CloseReturnPct();
            case 3 -> row.getT3CloseReturnPct();
            case 1 -> row.getT1CloseReturnPct();
            default -> null;
        };
    }

    private BigDecimal returnPct(BigDecimal entry, BigDecimal close) {
        return close.subtract(entry)
                .divide(entry, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private Extremes extremes(BigDecimal entry, List<MarketIndexDailyEntity> bars) {
        BigDecimal maxHigh = bars.stream().map(MarketIndexDailyEntity::getHighPrice)
                .filter(v -> v != null).max(Comparator.naturalOrder()).orElse(entry);
        BigDecimal minLow = bars.stream().map(MarketIndexDailyEntity::getLowPrice)
                .filter(v -> v != null).min(Comparator.naturalOrder()).orElse(entry);
        BigDecimal peak = entry;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        for (MarketIndexDailyEntity bar : bars) {
            BigDecimal high = bar.getHighPrice() != null ? bar.getHighPrice() : bar.getClosePrice();
            BigDecimal low = bar.getLowPrice() != null ? bar.getLowPrice() : bar.getClosePrice();
            if (high != null && high.compareTo(peak) > 0) peak = high;
            if (low != null && peak.signum() > 0) {
                BigDecimal drawdown = low.subtract(peak)
                        .divide(peak, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(4, RoundingMode.HALF_UP);
                if (drawdown.compareTo(maxDrawdown) < 0) maxDrawdown = drawdown;
            }
        }
        return new Extremes(returnPct(entry, maxHigh), returnPct(entry, minLow), maxDrawdown);
    }

    private boolean writeReturn(CandidateForwardTrackingEntity row, int horizon, BigDecimal value) {
        return switch (horizon) {
            case 1 -> setIfDifferent(row.getT1CloseReturnPct(), value, row::setT1CloseReturnPct);
            case 3 -> setIfDifferent(row.getT3CloseReturnPct(), value, row::setT3CloseReturnPct);
            case 5 -> setIfDifferent(row.getT5CloseReturnPct(), value, row::setT5CloseReturnPct);
            case 10 -> setIfDifferent(row.getT10CloseReturnPct(), value, row::setT10CloseReturnPct);
            default -> false;
        };
    }

    private boolean setIfDifferent(BigDecimal current, BigDecimal next, java.util.function.Consumer<BigDecimal> setter) {
        if (next == null) return false;
        if (current != null && current.compareTo(next) == 0) return false;
        setter.accept(next);
        return true;
    }

    private record Extremes(BigDecimal mfe, BigDecimal mae, BigDecimal maxDrawdown) { }

    private record FillResult(boolean changed, List<String> gaps, Integer benchmarkHorizon) {
        boolean hasDataGap() { return !gaps.isEmpty(); }
        Map<String, Object> sample(CandidateForwardTrackingEntity row) {
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("symbol", row.getStockId());
            sample.put("date", row.getTradingDate());
            sample.put("decision", row.getFinalDecision());
            sample.put("benchmarkHorizon", benchmarkHorizon == null ? "DATA_GAP" : "T" + benchmarkHorizon);
            sample.put("reason", gaps);
            return sample;
        }
    }
}
