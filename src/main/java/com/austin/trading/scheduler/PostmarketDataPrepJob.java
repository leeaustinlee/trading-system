package com.austin.trading.scheduler;

import com.austin.trading.client.MarketBreadthClient;
import com.austin.trading.client.TwseMisClient;
import com.austin.trading.client.dto.MarketBreadth;
import com.austin.trading.client.dto.StockQuote;
import com.austin.trading.dto.request.AiTaskCandidateRef;
import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.MarketSnapshotEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.MarketSnapshotRepository;
import com.austin.trading.service.AiTaskService;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.ClaudeCodeRequestWriterService;
import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.OrchestrationStep;
import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.service.ThemeLeaderRetentionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 15:05 收盤後資料準備排程。
 * <p>
 * 在 PostmarketAnalysis1530Job（15:30）之前執行：
 * 1. 抓取今日大盤漲跌家數與指數
 * 2. 抓取今日候選股收盤報價
 * 3. 更新 candidate_stock payload_json 加入收盤報價
 * 4. 儲存 market_snapshot（收盤版）
 * 5. v2.7：建立 POSTMARKET 單一 scoring universe + 單一 market context
 * </p>
 */
@Component
@ConditionalOnProperty(prefix = "trading.scheduler.postmarket-data-prep", name = "enabled", havingValue = "true")
public class PostmarketDataPrepJob {

    private static final Logger log = LoggerFactory.getLogger(PostmarketDataPrepJob.class);
    private static final List<Path> MARKET_BREADTH_SCAN_PATHS = List.of(
            Path.of("D:/ai/stock/market-breadth-scan.json"),
            Path.of("/mnt/d/ai/stock/market-breadth-scan.json")
    );
    private static final String MARKET_GRADE_SOURCE = "JAVA_POSTMARKET_1505";
    private static final String MARKET_GRADE_SOURCE_FALLBACK = "JAVA_POSTMARKET_1505_FALLBACK_NO_BREADTH";

    private final MarketBreadthClient             marketBreadthClient;
    private final TwseMisClient                   twseMisClient;
    private final CandidateScanService            candidateScanService;
    private final CandidateStockRepository        candidateStockRepository;
    private final MarketSnapshotRepository        marketSnapshotRepository;
    private final SchedulerLogService             schedulerLogService;
    private final ClaudeCodeRequestWriterService  requestWriterService;
    private final DailyOrchestrationService       orchestrationService;
    private final AiTaskService                   aiTaskService;
    private final ThemeLeaderRetentionService     themeLeaderRetentionService;
    private final ObjectMapper                    objectMapper;

    @Autowired
    public PostmarketDataPrepJob(
            MarketBreadthClient marketBreadthClient,
            TwseMisClient twseMisClient,
            CandidateScanService candidateScanService,
            CandidateStockRepository candidateStockRepository,
            MarketSnapshotRepository marketSnapshotRepository,
            SchedulerLogService schedulerLogService,
            ClaudeCodeRequestWriterService requestWriterService,
            DailyOrchestrationService orchestrationService,
            AiTaskService aiTaskService,
            ThemeLeaderRetentionService themeLeaderRetentionService,
            ObjectMapper objectMapper
    ) {
        this.marketBreadthClient      = marketBreadthClient;
        this.twseMisClient            = twseMisClient;
        this.candidateScanService     = candidateScanService;
        this.candidateStockRepository = candidateStockRepository;
        this.marketSnapshotRepository = marketSnapshotRepository;
        this.schedulerLogService      = schedulerLogService;
        this.requestWriterService     = requestWriterService;
        this.orchestrationService     = orchestrationService;
        this.aiTaskService            = aiTaskService;
        this.themeLeaderRetentionService = themeLeaderRetentionService;
        this.objectMapper             = objectMapper;
    }

    /** Backward-compatible constructor for legacy tests. */
    public PostmarketDataPrepJob(
            MarketBreadthClient marketBreadthClient,
            TwseMisClient twseMisClient,
            CandidateScanService candidateScanService,
            CandidateStockRepository candidateStockRepository,
            MarketSnapshotRepository marketSnapshotRepository,
            SchedulerLogService schedulerLogService,
            ClaudeCodeRequestWriterService requestWriterService,
            DailyOrchestrationService orchestrationService,
            AiTaskService aiTaskService,
            ObjectMapper objectMapper
    ) {
        this(marketBreadthClient, twseMisClient, candidateScanService, candidateStockRepository,
                marketSnapshotRepository, schedulerLogService, requestWriterService, orchestrationService,
                aiTaskService, null, objectMapper);
    }

    @Scheduled(cron = "${trading.scheduler.postmarket-data-prep-cron:0 5 15 * * MON-FRI}",
               zone  = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "PostmarketDataPrepJob";
        LocalDate today = LocalDate.now();
        OrchestrationStep step = OrchestrationStep.POSTMARKET_DATA_PREP;

        if (!orchestrationService.markRunning(today, step)) {
            log.info("[{}] Step {} already DONE today, skip.", jobName, step);
            return;
        }
        try {
            Optional<MarketBreadth> breadth = marketBreadthClient.getBreadth(today);

            List<CandidateResponse> currentCandidates = candidateScanService.getCurrentCandidates(20);
            UniverseBuildResult universe = buildUnifiedUniverse(today, currentCandidates);
            List<CandidateResponse> candidates = universe.candidates();
            List<String> symbols = candidates.stream()
                    .map(CandidateResponse::symbol)
                    .filter(s -> s != null && !s.isBlank())
                    .toList();

            Map<String, StockQuote> quoteMap = symbols.isEmpty()
                    ? Map.of()
                    : twseMisClient.getQuotesWithOtcFallback(symbols).stream()
                            .filter(q -> q.currentPrice() != null || q.prevClose() != null)
                            .collect(Collectors.toMap(StockQuote::symbol, q -> q, (a, b) -> a));

            List<CandidateStockEntity> entities =
                    candidateStockRepository.findByTradingDateOrderByScoreDesc(today, PageRequest.of(0, 20));

            int updated = 0;
            for (CandidateStockEntity entity : entities) {
                StockQuote q = quoteMap.get(entity.getSymbol());
                if (q == null) continue;
                String merged = mergePayload(entity.getPayloadJson(), q);
                entity.setPayloadJson(merged);
                candidateStockRepository.save(entity);
                updated++;
            }

            MarketGradeDecision marketGradeDecision = computeMarketGradeDecision(breadth.orElse(null));
            String marketGrade = marketGradeDecision.grade();
            breadth.ifPresent(b -> saveCloseSnapshot(today, b, marketGradeDecision));

            List<AiTaskCandidateRef> refs = candidates.stream()
                    .map(c -> new AiTaskCandidateRef(
                            c.symbol(), c.stockName(), c.themeTag(), c.javaStructureScore()))
                    .toList();
            String promptSummary = String.format(
                    "15:05 盤後 unified universe（共 %d 檔，source=%s，marketGrade=%s），等 Claude 15:18 / Codex 15:28 接手",
                    refs.size(), universe.universeSource(), marketGrade);
            var task = aiTaskService.createTask(
                    today, "POSTMARKET", null, refs,
                    promptSummary,
                    "D:/ai/stock/claude-research-request.json"
            );
            Long postmarketTaskId = task.getId();

            int retainedLeaders = 0;
            if (themeLeaderRetentionService != null) {
                retainedLeaders = themeLeaderRetentionService.retainPostmarketSuperStrong(
                        today, selectSuperStrongLeaders(candidates, universe.superStrongSymbols()));
            }

            String marketContext = buildMarketContextJson(breadth.orElse(null), marketGradeDecision, universe, symbols);
            boolean requestWritten = requestWriterService.writeRequest(postmarketTaskId, "POSTMARKET", today, symbols, marketContext);
            if (!requestWritten) {
                throw new IllegalStateException("POSTMARKET request 寫出失敗，拒絕留下只有 task、沒有 file bridge request 的狀態");
            }

            String msg = String.format(
                    "breadth=%s candidates=%d updated=%d universeSource=%s marketGrade=%s retainedLeaders=%d scoringSymbols=%s",
                    breadth.map(b -> b.advances() + "/" + b.declines()).orElse("N/A"),
                    candidates.size(),
                    updated,
                    universe.universeSource(),
                    marketGrade,
                    retainedLeaders,
                    String.join(",", symbols)
            );
            log.info("[PostmarketDataPrepJob] {}", msg);
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), msg);
            orchestrationService.markDone(today, step, msg);

        } catch (Exception e) {
            orchestrationService.markFailed(today, step, e.getMessage());
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private UniverseBuildResult buildUnifiedUniverse(LocalDate tradingDate, List<CandidateResponse> currentCandidates) {
        List<CandidateResponse> fallback = currentCandidates == null ? List.of() : currentCandidates;
        Map<String, CandidateResponse> currentBySymbol = new LinkedHashMap<>();
        for (CandidateResponse candidate : fallback) {
            if (candidate == null || candidate.symbol() == null || candidate.symbol().isBlank()) continue;
            currentBySymbol.put(candidate.symbol(), candidate);
        }

        Optional<Path> scanPath = resolveMarketBreadthScanPath();
        if (scanPath.isEmpty()) {
            log.info("[PostmarketDataPrepJob] market-breadth-scan.json missing from known paths {}, fallback to getCurrentCandidates() universe", MARKET_BREADTH_SCAN_PATHS);
            return new UniverseBuildResult(
                    trimToTen(fallback),
                    "FALLBACK_CURRENT_CANDIDATES",
                    List.of(),
                    List.of()
            );
        }

        try (var in = Files.newInputStream(scanPath.get())) {
            JsonNode root = objectMapper.readTree(in);
            List<String> superStrongSymbols = extractSymbols(root.path("super_strong_5"));
            List<String> finalCandidateSymbols = extractSymbols(root.path("final_candidates_5"));
            Set<String> orderedSymbols = new LinkedHashSet<>();
            orderedSymbols.addAll(superStrongSymbols);
            orderedSymbols.addAll(finalCandidateSymbols);

            List<CandidateResponse> unified = new ArrayList<>();
            for (String symbol : orderedSymbols) {
                if (unified.size() >= 10) break;
                CandidateResponse existing = currentBySymbol.get(symbol);
                if (existing != null) {
                    unified.add(existing);
                } else {
                    CandidateResponse fromScan = buildCandidateFromScan(tradingDate, root, symbol);
                    if (fromScan != null) {
                        unified.add(fromScan);
                    }
                }
            }

            if (!unified.isEmpty()) {
                return new UniverseBuildResult(unified, "FRESH_SCAN", superStrongSymbols, finalCandidateSymbols);
            }

            log.warn("[PostmarketDataPrepJob] fresh scan parsed but unified universe empty, fallback to getCurrentCandidates() universe");
            return new UniverseBuildResult(
                    trimToTen(fallback),
                    "FALLBACK_CURRENT_CANDIDATES",
                    superStrongSymbols,
                    finalCandidateSymbols
            );
        } catch (Exception e) {
            log.warn("[PostmarketDataPrepJob] failed to parse market-breadth-scan.json, fallback to getCurrentCandidates(): {}", e.getMessage());
            return new UniverseBuildResult(
                    trimToTen(fallback),
                    "FALLBACK_CURRENT_CANDIDATES",
                    List.of(),
                    List.of()
            );
        }
    }

    private List<CandidateResponse> selectSuperStrongLeaders(List<CandidateResponse> candidates, List<String> superStrongSymbols) {
        if (candidates == null || candidates.isEmpty() || superStrongSymbols == null || superStrongSymbols.isEmpty()) return List.of();
        Map<String, CandidateResponse> bySymbol = candidates.stream()
                .filter(c -> c != null && c.symbol() != null && !c.symbol().isBlank())
                .collect(Collectors.toMap(CandidateResponse::symbol, c -> c, (a, b) -> a, LinkedHashMap::new));
        List<CandidateResponse> leaders = new ArrayList<>();
        for (String symbol : superStrongSymbols) {
            CandidateResponse candidate = bySymbol.get(symbol);
            if (candidate != null) leaders.add(candidate);
        }
        return leaders;
    }

    private Optional<Path> resolveMarketBreadthScanPath() {
        String override = System.getProperty("trading.postmarket.marketBreadthScanPath");
        if (override != null && !override.isBlank()) {
            Path overridePath = Path.of(override);
            return Files.exists(overridePath) ? Optional.of(overridePath) : Optional.empty();
        }
        return MARKET_BREADTH_SCAN_PATHS.stream()
                .filter(Files::exists)
                .findFirst();
    }

    private List<String> extractSymbols(JsonNode arrayNode) {
        List<String> symbols = new ArrayList<>();
        if (arrayNode == null || !arrayNode.isArray()) return symbols;
        for (JsonNode item : arrayNode) {
            String symbol = textOrNull(item, "Code", "symbol", "stockCode");
            if (symbol != null && !symbol.isBlank() && !symbols.contains(symbol)) {
                symbols.add(symbol);
            }
        }
        return symbols;
    }

    private CandidateResponse buildCandidateFromScan(LocalDate tradingDate, JsonNode root, String symbol) {
        JsonNode item = findScanCandidate(root.path("super_strong_5"), symbol);
        if (item == null) item = findScanCandidate(root.path("final_candidates_5"), symbol);
        if (item == null) return null;

        BigDecimal score = decimalOrNull(item, "Score");
        String stockName = textOrNull(item, "Name", "stockName");
        String themeTag = textOrNull(item, "Theme", "themeTag");
        String reason = buildReasonFromScan(item, themeTag);

        return new CandidateResponse(
                tradingDate,
                symbol,
                stockName,
                score,
                reason,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                themeTag,
                null,
                score,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private JsonNode findScanCandidate(JsonNode arrayNode, String symbol) {
        if (arrayNode == null || !arrayNode.isArray() || symbol == null) return null;
        for (JsonNode item : arrayNode) {
            String code = textOrNull(item, "Code", "symbol", "stockCode");
            if (symbol.equals(code)) return item;
        }
        return null;
    }

    private String buildReasonFromScan(JsonNode item, String themeTag) {
        List<String> parts = new ArrayList<>();
        if (themeTag != null && !themeTag.isBlank()) parts.add(themeTag);
        BigDecimal changePct = decimalOrNull(item, "ChangePct");
        BigDecimal amountYi = decimalOrNull(item, "AmountYi");
        String tradability = textOrNull(item, "TradabilityTag");
        if (changePct != null) parts.add("漲幅 " + changePct.stripTrailingZeros().toPlainString() + "%");
        if (amountYi != null) parts.add("成交 " + amountYi.stripTrailingZeros().toPlainString() + " 億");
        if (tradability != null && !tradability.isBlank()) parts.add(tradability);
        return String.join("；", parts);
    }

    private BigDecimal decimalOrNull(JsonNode node, String fieldName) {
        JsonNode child = node == null ? null : node.get(fieldName);
        if (child == null || child.isNull()) return null;
        if (child.isNumber()) return child.decimalValue();
        String text = child.asText(null);
        if (text == null || text.isBlank()) return null;
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String textOrNull(JsonNode node, String... fieldNames) {
        if (node == null || fieldNames == null) return null;
        for (String fieldName : fieldNames) {
            JsonNode child = node.get(fieldName);
            if (child != null && !child.isNull()) {
                String text = child.asText();
                if (text != null && !text.isBlank()) return text.trim();
            }
        }
        return null;
    }

    private List<CandidateResponse> trimToTen(List<CandidateResponse> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        return candidates.stream().limit(10).toList();
    }

    private MarketGradeDecision computeMarketGradeDecision(MarketBreadth breadth) {
        if (breadth == null) return new MarketGradeDecision("B", MARKET_GRADE_SOURCE_FALLBACK);
        double ratio = breadth.advanceDeclineRatio();
        double indexPct = breadth.indexChangePercent() == null ? 0.0 : breadth.indexChangePercent();
        if (indexPct <= -0.8 || ratio < 0.85) return new MarketGradeDecision("C", MARKET_GRADE_SOURCE);
        if (indexPct >= 0.8 && ratio >= 1.2) return new MarketGradeDecision("A", MARKET_GRADE_SOURCE);
        return new MarketGradeDecision("B", MARKET_GRADE_SOURCE);
    }

    private String buildMarketContextJson(
            MarketBreadth breadth,
            MarketGradeDecision marketGradeDecision,
            UniverseBuildResult universe,
            List<String> scoringSymbols
    ) throws IOException {
        var root = objectMapper.createObjectNode();
        root.put("source", "postmarket_data_prep_v2");
        root.put("marketGrade", marketGradeDecision.grade());
        root.put("marketGradeSource", marketGradeDecision.source());
        root.put("universeSource", universe.universeSource());
        root.put("watchlistMixedIntoScoring", false);

        var scoringUniverse = root.putObject("scoringUniverse");
        scoringUniverse.put("count", scoringSymbols.size());
        scoringUniverse.put("maxSymbols", 10);
        var symbols = scoringUniverse.putArray("symbols");
        scoringSymbols.forEach(symbols::add);
        var superStrong = scoringUniverse.putArray("superStrongSymbols");
        universe.superStrongSymbols().forEach(superStrong::add);
        var finalCandidates = scoringUniverse.putArray("finalCandidateSymbols");
        universe.finalCandidateSymbols().forEach(finalCandidates::add);

        var breadthNode = root.putObject("breadth");
        if (breadth != null) {
            breadthNode.put("advances", breadth.advances());
            breadthNode.put("declines", breadth.declines());
            breadthNode.put("unchanged", breadth.unchanged());
            breadthNode.put("advanceDeclineRatio", breadth.advanceDeclineRatio());
            if (breadth.indexValue() != null) breadthNode.put("indexValue", breadth.indexValue());
            if (breadth.indexChange() != null) breadthNode.put("indexChange", breadth.indexChange());
            if (breadth.indexChangePercent() != null) breadthNode.put("indexChangePercent", breadth.indexChangePercent());
            if (breadth.tradeDate() != null) breadthNode.put("tradeDate", breadth.tradeDate());
        } else {
            breadthNode.put("warning", "MARKET_BREADTH_UNAVAILABLE");
        }

        root.put("notes", "POSTMARKET scoring universe is task-scoped. Fresh scan watchlist must not be mixed into this task scoring.");
        return objectMapper.writeValueAsString(root);
    }

    private void saveCloseSnapshot(LocalDate date, MarketBreadth breadth, MarketGradeDecision marketGradeDecision) {
        String payload = String.format(
                "{\"source\":\"postmarket_data_prep\",\"advances\":%d,\"declines\":%d,\"unchanged\":%d"
                        + ",\"index_value\":%s,\"index_change\":%s,\"index_change_pct\":%s,\"market_grade_source\":\"%s\"}",
                breadth.advances(), breadth.declines(), breadth.unchanged(),
                breadth.indexValue()         == null ? "null" : String.valueOf(breadth.indexValue()),
                breadth.indexChange()        == null ? "null" : String.valueOf(breadth.indexChange()),
                breadth.indexChangePercent() == null ? "null" : String.valueOf(breadth.indexChangePercent()),
                marketGradeDecision.source()
        );

        MarketSnapshotEntity entity = new MarketSnapshotEntity();
        entity.setTradingDate(date);
        entity.setMarketGrade(marketGradeDecision.grade());
        entity.setMarketPhase("CLOSE");
        entity.setDecision("WATCH");
        entity.setPayloadJson(payload);
        marketSnapshotRepository.save(entity);
    }

    private String mergePayload(String existingJson, StockQuote quote) {
        String closeData = String.format(
                "\"close_price\":%s,\"prev_close\":%s,\"day_high\":%s,\"day_low\":%s,\"volume\":%s",
                quote.currentPrice() != null ? String.valueOf(quote.currentPrice()) : "null",
                quote.prevClose()    != null ? String.valueOf(quote.prevClose()) : "null",
                quote.dayHigh()      != null ? String.valueOf(quote.dayHigh()) : "null",
                quote.dayLow()       != null ? String.valueOf(quote.dayLow()) : "null",
                quote.volume()       != null ? String.valueOf(quote.volume()) : "null"
        );

        if (existingJson == null || existingJson.isBlank() || !existingJson.trim().startsWith("{")) {
            return "{" + closeData + "}";
        }
        String trimmed = existingJson.trim();
        if (trimmed.equals("{}")) return "{" + closeData + "}";
        return "{" + closeData + "," + trimmed.substring(1);
    }

    private record UniverseBuildResult(
            List<CandidateResponse> candidates,
            String universeSource,
            List<String> superStrongSymbols,
            List<String> finalCandidateSymbols
    ) {}

    private record MarketGradeDecision(
            String grade,
            String source
    ) {}
}
