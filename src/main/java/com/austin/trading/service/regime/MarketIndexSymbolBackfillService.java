package com.austin.trading.service.regime;

import com.austin.trading.client.TwseHistoryClient;
import com.austin.trading.client.TwseHistoryClient.DailyBar;
import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.PaperTradeRepository;
import com.austin.trading.repository.PositionRepository;
import com.austin.trading.service.ScoreConfigService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MarketIndexSymbolBackfillService {

    private static final String BENCHMARK_SYMBOL = "t00";
    private static final int DEFAULT_SYMBOL_LIMIT = 50;

    private final TwseHistoryClient twseHistoryClient;
    private final MarketIndexBackfillService marketIndexBackfillService;
    private final PaperTradeRepository paperTradeRepository;
    private final CandidateForwardTrackingRepository forwardTrackingRepository;
    private final CandidateStockRepository candidateStockRepository;
    private final PositionRepository positionRepository;
    private final ScoreConfigService scoreConfigService;

    public MarketIndexSymbolBackfillService(TwseHistoryClient twseHistoryClient,
                                            MarketIndexBackfillService marketIndexBackfillService,
                                            PaperTradeRepository paperTradeRepository,
                                            CandidateForwardTrackingRepository forwardTrackingRepository,
                                            CandidateStockRepository candidateStockRepository,
                                            PositionRepository positionRepository,
                                            ScoreConfigService scoreConfigService) {
        this.twseHistoryClient = twseHistoryClient;
        this.marketIndexBackfillService = marketIndexBackfillService;
        this.paperTradeRepository = paperTradeRepository;
        this.forwardTrackingRepository = forwardTrackingRepository;
        this.candidateStockRepository = candidateStockRepository;
        this.positionRepository = positionRepository;
        this.scoreConfigService = scoreConfigService;
    }

    @Transactional
    public Map<String, Object> backfillSymbols(int days, String symbols) {
        return backfillSymbols(days, symbols, true, true, DEFAULT_SYMBOL_LIMIT);
    }

    @Transactional
    public Map<String, Object> backfillSymbols(int days,
                                               String symbols,
                                               boolean includePaperTrades,
                                               boolean includeCandidates,
                                               int maxSymbols) {
        int window = days > 0 ? days : 90;
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(window);
        return backfillSymbols(from, to, symbols, includePaperTrades, includeCandidates, maxSymbols);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> coverage(int days,
                                        String symbols,
                                        boolean includePaperTrades,
                                        boolean includeCandidates,
                                        int maxSymbols) {
        int window = days > 0 ? days : 90;
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(window);
        int limit = maxSymbols > 0 ? maxSymbols : DEFAULT_SYMBOL_LIMIT;
        List<String> requestedSymbols = parseSymbols(symbols);
        List<String> resolvedSymbols = requestedSymbols.isEmpty()
                ? resolveRecentSymbols(from, to, includePaperTrades, includeCandidates, limit)
                : requestedSymbols;
        LinkedHashSet<String> checkSymbols = new LinkedHashSet<>();
        checkSymbols.add(BENCHMARK_SYMBOL);
        checkSymbols.addAll(resolvedSymbols);
        List<Map<String, Object>> symbolStats = new ArrayList<>();
        int symbolsWithInsufficientBars = 0;
        for (String symbol : checkSymbols) {
            List<?> bars = marketIndexBackfillService.findBars(symbol, from, to);
            long count = bars.size();
            boolean sufficientForMa10 = count >= 10;
            boolean sufficientForAtr14 = count >= 15;
            if (!sufficientForMa10 || !sufficientForAtr14) symbolsWithInsufficientBars++;
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("symbol", symbol);
            stat.put("barCount", count);
            stat.put("sufficientForMA10", sufficientForMa10);
            stat.put("sufficientForATR14", sufficientForAtr14);
            stat.put("recommendedAction", sufficientForAtr14 ? "OK" : "BACKFILL_DAILY_BARS");
            symbolStats.add(stat);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("mode", "READ_ONLY_COVERAGE_ONLY");
        out.put("from", from);
        out.put("to", to);
        out.put("requestedSymbols", requestedSymbols);
        out.put("resolvedSymbols", resolvedSymbols);
        out.put("symbolCount", checkSymbols.size());
        out.put("symbolsWithInsufficientBars", symbolsWithInsufficientBars);
        out.put("symbolStats", symbolStats);
        out.put("safetyNote", "READ_ONLY: coverage report does not fetch, write, or change trading decisions; use POST /api/market-index/backfill-symbols to repair gaps manually");
        return out;
    }

    @Transactional
    public Map<String, Object> backfillSymbols(LocalDate from,
                                               LocalDate to,
                                               String symbols,
                                               boolean includePaperTrades,
                                               boolean includeCandidates,
                                               int maxSymbols) {
        if (from == null || to == null || from.isAfter(to)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("requestedSymbols", parseSymbols(symbols));
            out.put("resolvedSymbols", List.of());
            out.put("includePaperTrades", includePaperTrades);
            out.put("includeCandidates", includeCandidates);
            out.put("maxSymbols", maxSymbols > 0 ? maxSymbols : DEFAULT_SYMBOL_LIMIT);
            out.put("upsertedRows", 0);
            out.put("skippedSymbols", List.of());
            out.put("symbolStats", List.of());
            out.put("benchmarkDataGap", true);
            out.put("dataGaps", List.of(Map.of("reason", "DATA_GAP: invalid backfill date range")));
            out.put("from", from);
            out.put("to", to);
            return out;
        }
        List<String> requestedSymbols = parseSymbols(symbols);
        int limit = maxSymbols > 0 ? maxSymbols : DEFAULT_SYMBOL_LIMIT;
        List<String> resolvedSymbols = requestedSymbols.isEmpty()
                ? resolveRecentSymbols(from, to, includePaperTrades, includeCandidates, limit)
                : requestedSymbols;

        LinkedHashSet<String> fetchSymbols = new LinkedHashSet<>();
        fetchSymbols.add(BENCHMARK_SYMBOL);
        fetchSymbols.addAll(resolvedSymbols);

        int throttle = scoreConfigService != null
                ? Math.max(0, scoreConfigService.getInt(MarketIndexBackfillService.CFG_THROTTLE_MS, 250))
                : 250;
        int upserted = 0;
        List<String> skippedSymbols = new ArrayList<>();
        List<Map<String, Object>> dataGaps = new ArrayList<>();
        List<Map<String, Object>> symbolStats = new ArrayList<>();
        boolean benchmarkDataGap = false;
        YearMonth startYm = YearMonth.from(from);
        YearMonth endYm = YearMonth.from(to);

        for (String symbol : fetchSymbols) {
            if (symbol == null || symbol.isBlank()) continue;
            int symbolUpserted = 0;
            int symbolResolved = 0;
            List<String> symbolGaps = new ArrayList<>();
            for (YearMonth ym = startYm; !ym.isAfter(endYm); ym = ym.plusMonths(1)) {
                List<DailyBar> bars;
                try {
                    bars = BENCHMARK_SYMBOL.equals(symbol)
                            ? twseHistoryClient.fetchTaiexMonth(ym)
                            : twseHistoryClient.fetchStockMonth(symbol, ym);
                } catch (Exception e) {
                    bars = List.of();
                    symbolGaps.add("DATA_GAP: TWSE fetch failed " + ym + " " + e.getMessage());
                }
                if (bars == null || bars.isEmpty()) {
                    String reason = BENCHMARK_SYMBOL.equals(symbol)
                            ? "BENCHMARK_DATA_GAP: TWSE MI_5MINS_HIST returned no daily bars or HTML"
                            : "DATA_GAP: TWSE returned no daily bars";
                    addGap(dataGaps, symbol, ym, reason);
                    symbolGaps.add(reason + " month=" + ym);
                } else {
                    symbolResolved += bars.size();
                    int n = marketIndexBackfillService.upsertBars(bars, from, to);
                    symbolUpserted += n;
                    upserted += n;
                }
                sleepQuietly(throttle);
            }
            boolean benchmark = BENCHMARK_SYMBOL.equals(symbol);
            boolean dataGap = !symbolGaps.isEmpty();
            if (benchmark && dataGap) {
                benchmarkDataGap = true;
            }
            String skippedReason = null;
            if (symbolUpserted == 0) {
                if (symbolResolved > 0) {
                    skippedReason = "IDEMPOTENT_NO_CHANGES";
                } else if (benchmark) {
                    skippedReason = "BENCHMARK_DATA_GAP_ONLY";
                } else {
                    skippedReason = "DATA_GAP";
                }
                skippedSymbols.add(symbol);
            }
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("symbol", symbol);
            stat.put("requested", requestedSymbols.contains(symbol) || benchmark);
            stat.put("resolved", symbolResolved);
            stat.put("upsertedRows", symbolUpserted);
            stat.put("skippedReason", skippedReason);
            stat.put("dataGap", dataGap);
            if (!symbolGaps.isEmpty()) stat.put("dataGapReasons", symbolGaps.stream().limit(5).toList());
            symbolStats.add(stat);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestedSymbols", requestedSymbols);
        out.put("resolvedSymbols", resolvedSymbols);
        out.put("includePaperTrades", includePaperTrades);
        out.put("includeCandidates", includeCandidates);
        out.put("maxSymbols", limit);
        out.put("upsertedRows", upserted);
        out.put("skippedSymbols", skippedSymbols);
        out.put("symbolStats", symbolStats);
        out.put("benchmarkDataGap", benchmarkDataGap);
        out.put("dataGaps", dataGaps);
        out.put("from", from);
        out.put("to", to);
        return out;
    }

    private List<String> parseSymbols(String symbols) {
        if (symbols == null || symbols.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String part : symbols.split(",")) {
            String s = normalizeSymbol(part);
            if (s != null && !BENCHMARK_SYMBOL.equals(s)) out.add(s);
        }
        return new ArrayList<>(out);
    }

    private List<String> resolveRecentSymbols(LocalDate from,
                                              LocalDate to,
                                              boolean includePaperTrades,
                                              boolean includeCandidates,
                                              int limit) {
        LinkedHashSet<String> symbols = new LinkedHashSet<>();
        if (positionRepository != null) {
            for (PositionEntity position : positionRepository.findByStatus("OPEN")) {
                addSymbol(symbols, position.getSymbol(), limit);
            }
        }
        if (includePaperTrades) {
            for (PaperTradeEntity trade : paperTradeRepository.findByEntryDateBetweenOrderByEntryDateAscIdAsc(from, to)) {
                addSymbol(symbols, trade.getSymbol(), limit);
            }
        }
        if (includeCandidates) {
            for (CandidateForwardTrackingEntity row : forwardTrackingRepository.findByTradingDateBetween(from, to)) {
                addSymbol(symbols, row.getStockId(), limit);
            }
        }
        return new ArrayList<>(symbols);
    }

    private void addSymbol(Set<String> symbols, String raw, int limit) {
        if (symbols.size() >= limit) return;
        String symbol = normalizeSymbol(raw);
        if (symbol != null && !BENCHMARK_SYMBOL.equals(symbol)) symbols.add(symbol);
    }

    private String normalizeSymbol(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        return s.isBlank() ? null : s;
    }

    private void addGap(List<Map<String, Object>> gaps, String symbol, YearMonth ym, String reason) {
        if (gaps.size() >= 30) return;
        gaps.add(Map.of("symbol", symbol, "month", ym.toString(), "reason", reason));
    }

    private void sleepQuietly(int millis) {
        if (millis <= 0) return;
        try { Thread.sleep(millis); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
