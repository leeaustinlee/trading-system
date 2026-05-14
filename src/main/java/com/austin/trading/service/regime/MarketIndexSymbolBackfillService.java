package com.austin.trading.service.regime;

import com.austin.trading.client.TwseHistoryClient;
import com.austin.trading.client.TwseHistoryClient.DailyBar;
import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.PaperTradeRepository;
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
    private final ScoreConfigService scoreConfigService;

    public MarketIndexSymbolBackfillService(TwseHistoryClient twseHistoryClient,
                                            MarketIndexBackfillService marketIndexBackfillService,
                                            PaperTradeRepository paperTradeRepository,
                                            CandidateForwardTrackingRepository forwardTrackingRepository,
                                            CandidateStockRepository candidateStockRepository,
                                            ScoreConfigService scoreConfigService) {
        this.twseHistoryClient = twseHistoryClient;
        this.marketIndexBackfillService = marketIndexBackfillService;
        this.paperTradeRepository = paperTradeRepository;
        this.forwardTrackingRepository = forwardTrackingRepository;
        this.candidateStockRepository = candidateStockRepository;
        this.scoreConfigService = scoreConfigService;
    }

    @Transactional
    public Map<String, Object> backfillSymbols(int days, String symbols) {
        int window = days > 0 ? days : 90;
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(window);
        List<String> requestedSymbols = parseSymbols(symbols);
        List<String> resolvedSymbols = requestedSymbols.isEmpty()
                ? resolveRecentSymbols(from, to, DEFAULT_SYMBOL_LIMIT)
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
        YearMonth startYm = YearMonth.from(from);
        YearMonth endYm = YearMonth.from(to);

        for (String symbol : fetchSymbols) {
            if (symbol == null || symbol.isBlank()) continue;
            int before = upserted;
            for (YearMonth ym = startYm; !ym.isAfter(endYm); ym = ym.plusMonths(1)) {
                List<DailyBar> bars = BENCHMARK_SYMBOL.equals(symbol)
                        ? twseHistoryClient.fetchTaiexMonth(ym)
                        : twseHistoryClient.fetchStockMonth(symbol, ym);
                if (bars == null || bars.isEmpty()) {
                    addGap(dataGaps, symbol, ym, "DATA_GAP: TWSE returned no daily bars");
                } else {
                    upserted += marketIndexBackfillService.upsertBars(bars, from, to);
                }
                sleepQuietly(throttle);
            }
            if (before == upserted) {
                skippedSymbols.add(symbol);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestedSymbols", requestedSymbols);
        out.put("resolvedSymbols", resolvedSymbols);
        out.put("upsertedRows", upserted);
        out.put("skippedSymbols", skippedSymbols);
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

    private List<String> resolveRecentSymbols(LocalDate from, LocalDate to, int limit) {
        LinkedHashSet<String> symbols = new LinkedHashSet<>();
        for (PaperTradeEntity trade : paperTradeRepository.findByEntryDateBetweenOrderByEntryDateAscIdAsc(from, to)) {
            addSymbol(symbols, trade.getSymbol(), limit);
        }
        for (CandidateForwardTrackingEntity row : forwardTrackingRepository.findByTradingDateBetween(from, to)) {
            addSymbol(symbols, row.getStockId(), limit);
        }
        for (CandidateStockEntity row : candidateStockRepository.findByTradingDateBetweenOrderByTradingDateAscSymbolAsc(from, to)) {
            addSymbol(symbols, row.getSymbol(), limit);
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
