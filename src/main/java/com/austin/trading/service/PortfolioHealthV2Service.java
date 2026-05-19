package com.austin.trading.service;

import com.austin.trading.client.TwseMisClient;
import com.austin.trading.client.dto.StockQuote;
import com.austin.trading.engine.PositionHealthEngine;
import com.austin.trading.engine.PositionHealthInput;
import com.austin.trading.engine.PositionHealthResult;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.entity.StockThemeMappingEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.PositionRepository;
import com.austin.trading.repository.StockThemeMappingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PortfolioHealthV2Service {
    private final PositionRepository positionRepository;
    private final DailyTechnicalService dailyTechnicalService;
    private final TwseMisClient twseMisClient;
    private final PositionHealthEngine positionHealthEngine;
    private final CandidateStockRepository candidateStockRepository;
    private final StockThemeMappingRepository stockThemeMappingRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public PortfolioHealthV2Service(PositionRepository positionRepository,
                                    DailyTechnicalService dailyTechnicalService,
                                    TwseMisClient twseMisClient,
                                    PositionHealthEngine positionHealthEngine,
                                    CandidateStockRepository candidateStockRepository,
                                    StockThemeMappingRepository stockThemeMappingRepository,
                                    ObjectMapper objectMapper) {
        this.positionRepository = positionRepository;
        this.dailyTechnicalService = dailyTechnicalService;
        this.twseMisClient = twseMisClient;
        this.positionHealthEngine = positionHealthEngine;
        this.candidateStockRepository = candidateStockRepository;
        this.stockThemeMappingRepository = stockThemeMappingRepository;
        this.objectMapper = objectMapper;
    }

    public PortfolioHealthV2Service(PositionRepository positionRepository,
                                    DailyTechnicalService dailyTechnicalService,
                                    TwseMisClient twseMisClient,
                                    PositionHealthEngine positionHealthEngine) {
        this(positionRepository, dailyTechnicalService, twseMisClient, positionHealthEngine, null, null, new ObjectMapper());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> healthV2() {
        List<PositionEntity> positions = positionRepository.findByStatus("OPEN");
        Map<String, StockQuote> quotes = fetchQuotes(positions.stream().map(PositionEntity::getSymbol).toList());
        List<Map<String, Object>> rows = positions.stream()
                .map(p -> evaluate(p, quotes.get(p.getSymbol())))
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("mode", "SHADOW_MANUAL_CONFIRM_ONLY");
        out.put("autoBuyEnabled", false);
        out.put("autoSellEnabled", false);
        out.put("evaluatedAt", LocalDateTime.now());
        out.put("positionCount", rows.size());
        out.put("positions", rows);
        out.put("notice", "health-v2 只產生人工確認提醒；不會自動 BUY/SELL。正式出場仍需 Austin 人工確認。");
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> healthV2DataGaps() {
        Map<String, Object> health = healthV2();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> positions = (List<Map<String, Object>>) health.getOrDefault("positions", List.of());
        Map<String, Integer> byGap = new LinkedHashMap<>();
        Map<String, Integer> byTier = new LinkedHashMap<>();
        List<Map<String, Object>> affectedPositions = new java.util.ArrayList<>();
        for (Map<String, Object> row : positions) {
            String tier = String.valueOf(row.getOrDefault("actionTier", "UNKNOWN"));
            byTier.merge(tier, 1, Integer::sum);
            @SuppressWarnings("unchecked")
            List<String> gaps = (List<String>) row.getOrDefault("dataGaps", List.of());
            if (!gaps.isEmpty()) {
                for (String gap : gaps) byGap.merge(gap, 1, Integer::sum);
                affectedPositions.add(Map.of(
                        "symbol", row.get("symbol"),
                        "actionTier", tier,
                        "dataGaps", gaps,
                        "recommendedDataFix", recommendDataFix(gaps)));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("mode", "SHADOW_MANUAL_CONFIRM_ONLY");
        out.put("autoBuyEnabled", false);
        out.put("autoSellEnabled", false);
        out.put("evaluatedAt", health.get("evaluatedAt"));
        out.put("positionCount", positions.size());
        out.put("positionsWithDataGaps", affectedPositions.size());
        out.put("byActionTier", byTier);
        out.put("byDataGap", byGap);
        out.put("affectedPositions", affectedPositions);
        out.put("safetyNote", "READ_ONLY_DIAGNOSTIC_ONLY: data-gap summary does not change BUY/SELL/exit behavior");
        return out;
    }

    private Map<String, StockQuote> fetchQuotes(List<String> symbols) {
        if (twseMisClient == null || symbols == null || symbols.isEmpty()) return Map.of();
        try {
            return twseMisClient.getQuotesWithOtcFallback(symbols).stream()
                    .filter(q -> q.symbol() != null)
                    .collect(Collectors.toMap(StockQuote::symbol, q -> q, (a, b) -> a));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> evaluate(PositionEntity p, StockQuote quote) {
        BigDecimal current = quote != null && quote.currentPrice() != null ? BigDecimal.valueOf(quote.currentPrice()) : null;
        DailyTechnicalService.TechnicalSnapshot tech = dailyTechnicalService != null
                ? dailyTechnicalService.snapshot(p.getSymbol(), LocalDate.now())
                : DailyTechnicalService.TechnicalSnapshot.empty(List.of("DATA_GAP: daily technical service unavailable"));
        DailyTechnicalService.TechnicalSnapshot benchmark = dailyTechnicalService != null
                ? dailyTechnicalService.snapshot("t00", LocalDate.now())
                : DailyTechnicalService.TechnicalSnapshot.empty(List.of("DATA_GAP: benchmark daily data unavailable"));
        ThemeHealthContext theme = resolveThemeHealth(p.getSymbol());
        ChipHealthContext chip = resolveChipHealth(p.getSymbol());
        PositionHealthResult health = positionHealthEngine.evaluate(new PositionHealthInput(
                p.getSymbol(), p.getAvgCost(), current,
                tech.ma5(), tech.ma10(), tech.ma20(), tech.ma5Previous(), tech.previousLow(), tech.recentHigh(), tech.atr(), tech.volumeRatio(),
                tech.return5d(), benchmark.return5d(), tech.return10d(), benchmark.return10d(),
                theme.themeStage(), theme.mainstreamTheme(), chip.chipStatus()));
        List<String> dataGaps = new java.util.ArrayList<>(health.dataGaps());
        dataGaps.addAll(tech.dataGaps());
        dataGaps.addAll(benchmark.dataGaps().stream().map(g -> "BENCHMARK_" + g).toList());
        dataGaps.addAll(theme.dataGaps());
        dataGaps.addAll(chip.dataGaps());
        if (current == null) dataGaps.add("DATA_GAP: live current price missing");

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("positionId", p.getId());
        row.put("symbol", p.getSymbol());
        row.put("stockName", p.getStockName());
        row.put("strategyType", p.getStrategyType());
        row.put("avgCost", p.getAvgCost());
        row.put("currentPrice", current);
        row.put("stopLossPrice", p.getStopLossPrice());
        row.put("trailingStopPrice", p.getTrailingStopPrice());
        row.put("takeProfit1", p.getTakeProfit1());
        row.put("takeProfit2", p.getTakeProfit2());
        row.put("healthScore", health.healthScore());
        row.put("structureStatus", health.structureStatus());
        row.put("volumeStatus", health.volumeStatus());
        row.put("relativeStrengthStatus", health.relativeStrengthStatus());
        row.put("chipStatus", health.chipStatus());
        row.put("actionTier", mapTier(health.exitTier()));
        row.put("autoSellEnabled", false);
        row.put("manualConfirmRequired", true);
        row.put("reasons", health.reasons());
        row.put("dataGaps", dataGaps.stream().distinct().toList());
        row.put("healthInputs", Map.of(
                "mainstreamTheme", value(theme.mainstreamTheme()),
                "themeStage", value(theme.themeStage()),
                "chipStatus", value(chip.chipStatus()),
                "chipSourceDate", value(chip.sourceDate())));
        row.put("technicals", Map.ofEntries(
                Map.entry("ma5", value(tech.ma5())), Map.entry("ma10", value(tech.ma10())), Map.entry("ma20", value(tech.ma20())),
                Map.entry("ma5Previous", value(tech.ma5Previous())), Map.entry("previousLow", value(tech.previousLow())),
                Map.entry("recentHigh", value(tech.recentHigh())), Map.entry("atr", value(tech.atr())),
                Map.entry("volumeRatio", value(tech.volumeRatio())), Map.entry("return5d", value(tech.return5d())), Map.entry("return10d", value(tech.return10d()))));
        return row;
    }

    private String mapTier(PositionHealthResult.ExitTier tier) {
        if (tier == null) return "SOFT_WARNING";
        return switch (tier) {
            case HOLD -> "HOLD";
            case SOFT_WARNING -> "SOFT_WARNING";
            case REDUCE -> "REDUCE_REVIEW";
            case EXIT_CONFIRM_REQUIRED -> "EXIT_REVIEW";
            case HARD_EXIT -> "HARD_EXIT_ALERT";
        };
    }

    private ThemeHealthContext resolveThemeHealth(String symbol) {
        if (stockThemeMappingRepository == null || symbol == null || symbol.isBlank()) {
            return new ThemeHealthContext(null, null, List.of("DATA_GAP: theme mapping repository unavailable"));
        }
        List<StockThemeMappingEntity> mappings = stockThemeMappingRepository.findBySymbolAndIsActiveTrue(symbol);
        if (mappings.isEmpty()) return new ThemeHealthContext(null, null, List.of("DATA_GAP: mainstream theme mapping missing"));
        boolean mainstream = mappings.stream().anyMatch(m -> isMainstreamTheme(m.getThemeCategory(), m.getThemeTag()));
        String stage = mainstream ? "ACTIVE" : "UNKNOWN";
        return new ThemeHealthContext(stage, mainstream, List.of());
    }

    private boolean isMainstreamTheme(String category, String tag) {
        String c = category == null ? "" : category.trim().toUpperCase();
        String t = tag == null ? "" : tag.trim().toUpperCase();
        return !(c.isBlank() || "OTHER".equals(c) || "UNKNOWN".equals(c))
                || MainstreamThemeNormalizer.normalize(tag, tag) != null
                && !List.of("UNKNOWN", "OTHER").contains(MainstreamThemeNormalizer.normalize(tag, tag));
    }

    private ChipHealthContext resolveChipHealth(String symbol) {
        if (candidateStockRepository == null || objectMapper == null || symbol == null || symbol.isBlank()) {
            return new ChipHealthContext("UNKNOWN", null, List.of("DATA_GAP: chip source unavailable"));
        }
        return candidateStockRepository.findTopBySymbolOrderByTradingDateDesc(symbol)
                .map(c -> chipFromCandidate(c))
                .orElse(new ChipHealthContext("UNKNOWN", null, List.of("DATA_GAP: latest candidate chip payload missing")));
    }

    private ChipHealthContext chipFromCandidate(CandidateStockEntity c) {
        try {
            String json = c.getPayloadJson();
            if (json == null || json.isBlank() || !json.trim().startsWith("{")) {
                return new ChipHealthContext("UNKNOWN", c.getTradingDate(), List.of("DATA_GAP: candidate institutional payload missing"));
            }
            JsonNode n = objectMapper.readTree(json);
            boolean bothBuy = n.path("foreign_and_trust_buy").asBoolean(false);
            Long total = n.hasNonNull("total_institutional_net") ? n.path("total_institutional_net").asLong() : null;
            Long foreign = n.hasNonNull("foreign_net") ? n.path("foreign_net").asLong() : null;
            Long trust = n.hasNonNull("invest_trust_net") ? n.path("invest_trust_net").asLong() : null;
            if (bothBuy) return new ChipHealthContext("BULLISH", c.getTradingDate(), List.of());
            if (total != null && total < 0 && (foreign == null || foreign < 0) && (trust == null || trust <= 0)) {
                return new ChipHealthContext("BEARISH", c.getTradingDate(), List.of());
            }
            if (total != null) return new ChipHealthContext("NEUTRAL", c.getTradingDate(), List.of());
            return new ChipHealthContext("UNKNOWN", c.getTradingDate(), List.of("DATA_GAP: institutional net fields missing"));
        } catch (Exception e) {
            return new ChipHealthContext("UNKNOWN", c.getTradingDate(), List.of("DATA_GAP: candidate institutional payload parse failed"));
        }
    }

    private String recommendDataFix(List<String> gaps) {
        String joined = String.join(" | ", gaps).toLowerCase();
        if (joined.contains("daily bars") || joined.contains("ma")) return "補個股 market_index_daily 日線與均線所需資料";
        if (joined.contains("benchmark")) return "補 t00/TAIEX market_index_daily 日線資料";
        if (joined.contains("chip")) return "補法人/投信/外資籌碼資料後再判讀持股健康度";
        if (joined.contains("mainstream theme") || joined.contains("theme")) return "補 stock_theme_mapping / theme taxonomy，避免題材健康度缺失";
        if (joined.contains("live current price")) return "修正即時報價來源或 OTC fallback，再重新評估";
        return "保留人工確認；資料補齊前不要自動升級為正式出場訊號";
    }

    private Object value(Object v) { return v == null ? "DATA_GAP" : v; }

    private record ThemeHealthContext(String themeStage, Boolean mainstreamTheme, List<String> dataGaps) {}
    private record ChipHealthContext(String chipStatus, LocalDate sourceDate, List<String> dataGaps) {}
}
