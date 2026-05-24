package com.austin.trading.service;

import com.austin.trading.dto.response.HotGroupRadarResponse;
import com.austin.trading.entity.CandidateThemeRadarTraceEntity;
import com.austin.trading.entity.HotGroupRadarSnapshotEntity;
import com.austin.trading.entity.HotGroupStockSignalEntity;
import com.austin.trading.repository.CandidateThemeRadarTraceRepository;
import com.austin.trading.repository.HotGroupRadarSnapshotRepository;
import com.austin.trading.repository.HotGroupStockSignalRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
public class HotGroupRadarService {
    private static final Path DEFAULT_SCAN_PATH = Path.of("/mnt/d/ai/stock/market-breadth-scan.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> MARKET_LISTS = Set.of("hot_stocks", "limit_indicators", "super_strong_5", "tradable_pool", "tier2_pool", "affordable_tradable_pool", "final_candidates_5");
    private final HotGroupRadarSnapshotRepository snapshotRepo;
    private final HotGroupStockSignalRepository signalRepo;
    private final CandidateThemeRadarTraceRepository traceRepo;
    private final Map<LocalDate, Map<String, StockSignal>> lastUniverseByDate = new HashMap<>();
    private final Map<LocalDate, Set<String>> lastHotByDate = new HashMap<>();
    private final Map<LocalDate, Set<String>> lastFinalByDate = new HashMap<>();

    public HotGroupRadarService(HotGroupRadarSnapshotRepository snapshotRepo,
                                HotGroupStockSignalRepository signalRepo,
                                CandidateThemeRadarTraceRepository traceRepo) {
        this.snapshotRepo = snapshotRepo;
        this.signalRepo = signalRepo;
        this.traceRepo = traceRepo;
    }

    public HotGroupRadarResponse.SafetyBoundary safetyBoundary() {
        return HotGroupRadarResponse.SafetyBoundary.shadowOnlyBoundary();
    }

    @Transactional
    public HotGroupRadarResponse buildFromDefaultFile(LocalDate date, String phase) {
        try {
            String json = Files.readString(DEFAULT_SCAN_PATH, StandardCharsets.UTF_8);
            return build(date, phase, json);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read market-breadth-scan.json: " + DEFAULT_SCAN_PATH, e);
        }
    }

    @Transactional
    public HotGroupRadarResponse build(LocalDate date, String phase, String marketBreadthJson) {
        String sourcePhase = normalizePhase(phase);
        JsonNode root = parse(marketBreadthJson);
        Map<String, StockSignal> universe = collectUniverse(root);
        addPassivePeerSeedsIfThemeDetected(universe);
        Set<String> hotSymbols = symbols(root, "hot_stocks");
        Set<String> finalSymbols = symbols(root, "final_candidates_5");
        lastUniverseByDate.put(date, universe);
        lastHotByDate.put(date, hotSymbols);
        lastFinalByDate.put(date, finalSymbols);

        snapshotRepo.deleteByTradingDateAndSourcePhase(date, sourcePhase);
        signalRepo.deleteByTradingDateAndSourcePhase(date, sourcePhase);
        traceRepo.deleteByTradingDate(date);

        Map<String, List<StockSignal>> byTheme = universe.values().stream()
                .filter(s -> s.radarTheme != null)
                .collect(Collectors.groupingBy(s -> s.radarTheme, LinkedHashMap::new, Collectors.toList()));

        List<HotGroupRadarResponse.ThemeItem> themeItems = new ArrayList<>();
        List<HotGroupRadarResponse.SignalItem> signalItems = new ArrayList<>();

        byTheme.entrySet().stream()
                .sorted((a, b) -> scoreTheme(b.getValue()).compareTo(scoreTheme(a.getValue())))
                .forEach(entry -> {
                    String theme = entry.getKey();
                    List<StockSignal> stocks = entry.getValue().stream()
                            .sorted(Comparator.comparing(StockSignal::radarRankScore).reversed())
                            .toList();
                    HotGroupRadarSnapshotEntity snapshot = snapshotEntity(date, sourcePhase, theme, stocks);
                    snapshotRepo.save(snapshot);
                    themeItems.add(toThemeItem(snapshot));

                    for (int i = 0; i < stocks.size(); i++) {
                        StockSignal stock = stocks.get(i);
                        HotGroupStockSignalEntity signal = signalEntity(date, sourcePhase, stock, i == 0);
                        signalRepo.save(signal);
                        signalItems.add(toSignalItem(signal));
                        traceRepo.save(traceEntity(date, signal));
                    }
                });

        return new HotGroupRadarResponse(date, sourcePhase, true, true, true, true, true,
                safetyBoundary(), themeItems, signalItems);
    }

    public HotGroupRadarResponse radar(LocalDate date, String phase) {
        String sourcePhase = normalizePhase(phase);
        List<HotGroupRadarSnapshotEntity> snapshots = nullToEmpty(snapshotRepo.findByTradingDateAndSourcePhaseOrderByHotScoreDesc(date, sourcePhase));
        List<HotGroupStockSignalEntity> signals = nullToEmpty(signalRepo.findByTradingDateAndSourcePhaseOrderByRadarRankScoreDesc(date, sourcePhase));
        return new HotGroupRadarResponse(date, sourcePhase, true, true, true, true, true, safetyBoundary(),
                snapshots.stream().map(this::toThemeItem).toList(), signals.stream().map(this::toSignalItem).toList());
    }

    public HotGroupRadarResponse byTheme(LocalDate date, String themeTag) {
        List<HotGroupRadarSnapshotEntity> snapshots = nullToEmpty(snapshotRepo.findByTradingDateAndThemeTagOrderByHotScoreDesc(date, themeTag));
        List<HotGroupStockSignalEntity> signals = nullToEmpty(signalRepo.findByTradingDateAndThemeTagOrderByRadarRankScoreDesc(date, themeTag));
        String phase = snapshots.isEmpty() ? "POSTMARKET" : snapshots.get(0).getSourcePhase();
        return new HotGroupRadarResponse(date, phase, true, true, true, true, true, safetyBoundary(),
                snapshots.stream().map(this::toThemeItem).toList(), signals.stream().map(this::toSignalItem).toList());
    }

    public HotGroupRadarResponse.ExplainMiss explainMiss(LocalDate date, String symbol) {
        Map<String, StockSignal> universe = lastUniverseByDate.getOrDefault(date, Map.of());
        StockSignal stock = universe.get(symbol);
        List<HotGroupStockSignalEntity> dbSignals = nullToEmpty(signalRepo.findByTradingDateAndSymbolOrderByRadarRankScoreDesc(date, symbol));
        PersistedSignalEvidence evidence = dbSignals.stream()
                .map(HotGroupStockSignalEntity::getEvidenceJson)
                .map(this::persistedEvidence)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(PersistedSignalEvidence.empty());
        boolean radarWatch = dbSignals.stream().anyMatch(s -> "WATCH_ONLY".equals(s.getTradabilityTag()) || "REJECT_LIMIT_RISK".equals(s.getCandidateAction()));
        if (!radarWatch && stock != null && stock.radarTheme != null) radarWatch = true;
        boolean inUniverse = evidence.inUniverse() || stock != null || !dbSignals.isEmpty();
        boolean inHot = evidence.inHotStock() || lastHotByDate.getOrDefault(date, Set.of()).contains(symbol) || (stock != null && stock.inHotStock);
        boolean inFinal = evidence.inFinalCandidate() || lastFinalByDate.getOrDefault(date, Set.of()).contains(symbol) || (stock != null && stock.inFinalCandidate);
        boolean limitRisk = (stock != null && stock.limitRisk) || dbSignals.stream().anyMatch(s -> Boolean.TRUE.equals(s.getLimitRisk()));
        boolean affordabilityFail = stock != null && stock.boardLotCost.compareTo(new BigDecimal("160000")) > 0;
        boolean classifiedOther = "其他強勢股".equals(evidence.originalTheme()) || (stock != null && "其他強勢股".equals(stock.originalTheme));
        List<String> reasons = new ArrayList<>();
        if (limitRisk) reasons.add("limit_risk");
        if (affordabilityFail) reasons.add("affordability_fail");
        if (!inFinal) reasons.add("not_in_final_candidates_5");
        if (classifiedOther) reasons.add("classified_as_other_before_radar");
        if (radarWatch) reasons.add("radar_watch_only");
        return new HotGroupRadarResponse.ExplainMiss(date, symbol, inUniverse, inHot, classifiedOther,
                limitRisk, affordabilityFail, !inFinal, radarWatch, true, reasons, safetyBoundary());
    }

    public HotGroupRadarResponse.CandidateFeed candidateFeed(LocalDate date, String phase) {
        List<HotGroupRadarResponse.SignalItem> signals = radar(date, phase).signals();
        List<HotGroupRadarResponse.SignalItem> reject = signals.stream().filter(s -> "REJECT_LIMIT_RISK".equals(s.candidateAction())).toList();
        List<HotGroupRadarResponse.SignalItem> boost = signals.stream().filter(s -> "BOOST_EXISTING_CANDIDATE".equals(s.candidateAction())).toList();
        List<HotGroupRadarResponse.SignalItem> add = signals.stream().filter(s -> "ADD_TO_CANDIDATE_POOL_SHADOW".equals(s.candidateAction())).toList();
        List<HotGroupRadarResponse.SignalItem> watch = signals.stream().filter(s -> "WATCH_ONLY".equals(s.tradabilityTag()) || "WATCH_ONLY".equals(s.candidateAction()) || "REJECT_LIMIT_RISK".equals(s.candidateAction())).toList();
        return new HotGroupRadarResponse.CandidateFeed(date, normalizePhase(phase), true, true, true, safetyBoundary(), watch, boost, add, reject);
    }

    private Map<String, StockSignal> collectUniverse(JsonNode root) {
        Map<String, StockSignal> out = new LinkedHashMap<>();
        for (String listName : MARKET_LISTS) {
            JsonNode arr = root.path(listName);
            if (!arr.isArray()) continue;
            for (JsonNode node : arr) {
                StockSignal s = StockSignal.from(node, listName);
                if (s == null) continue;
                StockSignal prev = out.get(s.symbol);
                out.put(s.symbol, prev == null ? s : prev.merge(s));
            }
        }
        return out;
    }

    private Set<String> symbols(JsonNode root, String listName) {
        JsonNode arr = root.path(listName);
        if (!arr.isArray()) return Set.of();
        return StreamSupport.stream(arr.spliterator(), false).map(n -> n.path("Code").asText(null)).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private void addPassivePeerSeedsIfThemeDetected(Map<String, StockSignal> universe) {
        boolean hasPassiveLeader = universe.values().stream().anyMatch(s -> s.radarTheme != null && s.radarTheme.startsWith("被動元件/"));
        if (!hasPassiveLeader) return;
        passiveSeed("2327", "國巨*", universe);
        passiveSeed("2492", "華新科", universe);
        passiveSeed("2375", "凱美", universe);
        passiveSeed("3090", "日電貿", universe);
        passiveSeed("2472", "立隆電", universe);
        passiveSeed("6127", "九豪", universe);
    }

    private void passiveSeed(String symbol, String name, Map<String, StockSignal> universe) {
        if (universe.containsKey(symbol)) return;
        String theme = ThemeTaxonomyClassifier.classify(symbol, name);
        if (theme == null) return;
        universe.put(symbol, new StockSignal(symbol, name, "HOT_GROUP_RADAR_SEED", theme,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false,
                false, false, false, new LinkedHashSet<>(Set.of("taxonomy_peer_seed"))));
    }

    private HotGroupRadarSnapshotEntity snapshotEntity(LocalDate date, String phase, String theme, List<StockSignal> stocks) {
        int leaderCount = (int) stocks.stream().filter(s -> s.limitRisk || s.inSuperStrong || s.inHotStock).count();
        int limitUp = (int) stocks.stream().filter(s -> s.limitRisk || s.changePct.compareTo(new BigDecimal("9.2")) >= 0).count();
        int nearLimit = (int) stocks.stream().filter(s -> s.nearHigh.compareTo(new BigDecimal("0.995")) >= 0).count();
        int upCount = (int) stocks.stream().filter(s -> s.changePct.compareTo(BigDecimal.ZERO) > 0).count();
        BigDecimal totalTurnover = stocks.stream().map(s -> s.turnoverYi).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avgChange = avg(stocks.stream().map(s -> s.changePct).toList());
        BigDecimal diffusion = BigDecimal.valueOf(upCount).multiply(new BigDecimal("2.5"));
        BigDecimal score = scoreTheme(stocks);
        HotGroupRadarSnapshotEntity e = new HotGroupRadarSnapshotEntity();
        e.setTradingDate(date); e.setSourcePhase(phase); e.setThemeTag(theme); e.setThemeCategory(ThemeTaxonomyClassifier.categoryOf(theme));
        e.setHotScore(score); e.setLeaderCount(leaderCount); e.setLimitUpCount(limitUp); e.setNearLimitCount(nearLimit); e.setUpStockCount(upCount);
        e.setAvgChangePct(avgChange); e.setTotalTurnoverYi(totalTurnover); e.setDiffusionScore(diffusion); e.setNewsScore(BigDecimal.ZERO); e.setPriceHikeSignal(false);
        e.setRiskLevel(limitUp >= 1 ? "HIGH" : "MEDIUM");
        e.setEvidenceJson("{\"shadowOnly\":true,\"newsScore\":0,\"priceHikeSignal\":false,\"symbols\":" + stocks.stream().map(s -> "\"" + s.symbol + "\"").collect(Collectors.joining(",", "[", "]")) + "}");
        return e;
    }

    private HotGroupStockSignalEntity signalEntity(LocalDate date, String phase, StockSignal stock, boolean firstInTheme) {
        HotGroupStockSignalEntity e = new HotGroupStockSignalEntity();
        e.setTradingDate(date); e.setSourcePhase(phase); e.setThemeTag(stock.radarTheme); e.setSymbol(stock.symbol); e.setStockName(stock.name);
        e.setRole(role(stock, firstInTheme)); e.setChangePct(stock.changePct); e.setTurnoverYi(stock.turnoverYi); e.setNearHigh(stock.nearHigh); e.setLimitRisk(stock.limitRisk); e.setBoardLotCost(stock.boardLotCost);
        e.setRadarRankScore(stock.radarRankScore());
        if (stock.limitRisk) { e.setTradabilityTag("WATCH_ONLY"); e.setCandidateAction("REJECT_LIMIT_RISK"); e.setRejectionReason("limit_risk; shadow-only; not a tradable candidate"); }
        else if (stock.inFinalCandidate) { e.setTradabilityTag("WATCH_ONLY"); e.setCandidateAction("BOOST_EXISTING_CANDIDATE"); e.setRejectionReason("shadow boost only; no production score write"); }
        else if (stock.hasBasicThemeVolume()) { e.setTradabilityTag("WATCH_ONLY"); e.setCandidateAction("ADD_TO_CANDIDATE_POOL_SHADOW"); e.setRejectionReason("theme radar shadow pool only; does not write candidate_stock or final decision"); }
        else { e.setTradabilityTag("WATCH_ONLY"); e.setCandidateAction("WATCH_ONLY"); e.setRejectionReason("theme radar watch-only; does not write candidate_stock"); }
        e.setEvidenceJson(signalEvidenceJson(stock));
        return e;
    }

    private CandidateThemeRadarTraceEntity traceEntity(LocalDate date, HotGroupStockSignalEntity signal) {
        CandidateThemeRadarTraceEntity t = new CandidateThemeRadarTraceEntity();
        t.setTradingDate(date); t.setSymbol(signal.getSymbol()); t.setThemeTag(signal.getThemeTag());
        t.setCandidateBeforeScore(BigDecimal.ZERO); t.setThemeRadarBoost(BigDecimal.ZERO); t.setCandidateAfterScore(BigDecimal.ZERO);
        t.setAppliedToCandidatePool(false); t.setAppliedToFinalDecision(false);
        t.setSafetyContractJson("{\"shadowOnly\":true,\"observabilityOnly\":true,\"noDirectBuy\":true,\"doesNotWriteCandidateStock\":true,\"doesNotAffectFinalDecision\":true}");
        return t;
    }

    private String role(StockSignal s, boolean firstInTheme) {
        if ("被動元件/通路代理".equals(s.radarTheme)) return "CHANNEL_DISTRIBUTOR";
        if (s.sourceLists.contains("taxonomy_peer_seed") && s.changePct.compareTo(BigDecimal.ZERO) == 0) return "WATCH_ONLY";
        if (!s.limitRisk && s.changePct.compareTo(new BigDecimal("5")) < 0) return "LOW_BASE_FOLLOWER";
        if (firstInTheme && (s.limitRisk || s.inSuperStrong || s.turnoverYi.compareTo(new BigDecimal("50")) >= 0)) return "THEME_LEADER";
        if (s.limitRisk || s.changePct.compareTo(new BigDecimal("7")) >= 0) return "SECOND_LEADER";
        return "LOW_BASE_FOLLOWER";
    }

    private BigDecimal scoreTheme(List<StockSignal> stocks) {
        int leaders = (int) stocks.stream().filter(s -> s.limitRisk || s.inSuperStrong || s.inHotStock).count();
        int limit = (int) stocks.stream().filter(s -> s.limitRisk).count();
        int near = (int) stocks.stream().filter(s -> s.nearHigh.compareTo(new BigDecimal("0.995")) >= 0).count();
        int up = (int) stocks.stream().filter(s -> s.changePct.compareTo(BigDecimal.ZERO) > 0).count();
        BigDecimal turnover = stocks.stream().map(s -> s.turnoverYi).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avgChange = avg(stocks.stream().map(s -> s.changePct).toList());
        BigDecimal continuationHint = BigDecimal.valueOf(stocks.stream().filter(s -> s.inHotStock || s.inSuperStrong).count()).multiply(new BigDecimal("2"));
        BigDecimal crowdingRisk = BigDecimal.valueOf(limit).multiply(new BigDecimal("1.5"));
        return BigDecimal.valueOf(leaders * 5L + limit * 4L + near * 2L + up * 2L)
                .add(turnover.multiply(new BigDecimal("0.10"))).add(avgChange.multiply(new BigDecimal("1.5")))
                .add(continuationHint).subtract(crowdingRisk).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal avg(List<BigDecimal> values) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }

    private HotGroupRadarResponse.ThemeItem toThemeItem(HotGroupRadarSnapshotEntity e) {
        return new HotGroupRadarResponse.ThemeItem(e.getThemeTag(), e.getThemeCategory(), nz(e.getHotScore()), nzi(e.getLeaderCount()), nzi(e.getLimitUpCount()), nzi(e.getNearLimitCount()), nzi(e.getUpStockCount()), nz(e.getAvgChangePct()), nz(e.getTotalTurnoverYi()), nz(e.getDiffusionScore()), nz(e.getNewsScore()), Boolean.TRUE.equals(e.getPriceHikeSignal()), e.getRiskLevel());
    }

    private HotGroupRadarResponse.SignalItem toSignalItem(HotGroupStockSignalEntity e) {
        return new HotGroupRadarResponse.SignalItem(e.getThemeTag(), e.getSymbol(), e.getStockName(), e.getRole(), nz(e.getChangePct()), nz(e.getTurnoverYi()), nz(e.getNearHigh()), Boolean.TRUE.equals(e.getLimitRisk()), nz(e.getBoardLotCost()), e.getTradabilityTag(), nz(e.getRadarRankScore()), e.getCandidateAction(), e.getRejectionReason());
    }

    private JsonNode parse(String json) {
        String normalized = json;
        if (normalized != null && !normalized.isEmpty() && normalized.charAt(0) == '\uFEFF') {
            normalized = normalized.substring(1);
        }
        try { return MAPPER.readTree(normalized); } catch (IOException e) { throw new IllegalArgumentException("Invalid market-breadth-scan JSON", e); }
    }
    private PersistedSignalEvidence persistedEvidence(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode node = MAPPER.readTree(json);
            return new PersistedSignalEvidence(
                    node.path("inUniverse").asBoolean(false),
                    node.path("inHotStock").asBoolean(false),
                    node.path("inFinalCandidate").asBoolean(false),
                    node.path("originalTheme").asText(""));
        } catch (IOException e) {
            return null;
        }
    }
    private String signalEvidenceJson(StockSignal stock) {
        var node = MAPPER.createObjectNode();
        node.put("originalTheme", stock.originalTheme);
        var sourceLists = node.putArray("sourceLists");
        stock.sourceLists.forEach(sourceLists::add);
        node.put("inUniverse", true);
        node.put("inHotStock", stock.inHotStock);
        node.put("inFinalCandidate", stock.inFinalCandidate);
        return node.toString();
    }
    private String normalizePhase(String phase) { return (phase == null || phase.isBlank()) ? "POSTMARKET" : phase.trim().toUpperCase(Locale.ROOT); }
    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static int nzi(Integer v) { return v == null ? 0 : v; }
    private static String safe(String s) { return s == null ? "" : s.replace("\"", "'"); }
    private static <T> List<T> nullToEmpty(List<T> v) { return v == null ? List.of() : v; }

    private record StockSignal(String symbol, String name, String originalTheme, String radarTheme,
                               BigDecimal changePct, BigDecimal turnoverYi, BigDecimal nearHigh,
                               BigDecimal score, BigDecimal boardLotCost, boolean limitRisk,
                               boolean inHotStock, boolean inSuperStrong, boolean inFinalCandidate,
                               Set<String> sourceLists) {
        static StockSignal from(JsonNode n, String sourceList) {
            String symbol = n.path("Code").asText(null);
            String name = n.path("Name").asText("");
            if (symbol == null || symbol.isBlank()) return null;
            String radarTheme = ThemeTaxonomyClassifier.classify(symbol, name);
            if (radarTheme == null) return null;
            Set<String> lists = new LinkedHashSet<>(); lists.add(sourceList);
            return new StockSignal(symbol, name, n.path("Theme").asText(""), radarTheme,
                    bd(n, "ChangePct"), bd(n, "AmountYi"), bd(n, "NearHigh"), bd(n, "Score"), bd(n, "BoardLotCost"), n.path("IsLimitRisk").asBoolean(false),
                    "hot_stocks".equals(sourceList), "super_strong_5".equals(sourceList), "final_candidates_5".equals(sourceList), lists);
        }
        StockSignal merge(StockSignal other) {
            Set<String> lists = new LinkedHashSet<>(sourceLists); lists.addAll(other.sourceLists);
            return new StockSignal(symbol, !name.isBlank() ? name : other.name, !originalTheme.isBlank() ? originalTheme : other.originalTheme, radarTheme,
                    max(changePct, other.changePct), max(turnoverYi, other.turnoverYi), max(nearHigh, other.nearHigh), max(score, other.score), max(boardLotCost, other.boardLotCost), limitRisk || other.limitRisk,
                    inHotStock || other.inHotStock, inSuperStrong || other.inSuperStrong, inFinalCandidate || other.inFinalCandidate, lists);
        }
        BigDecimal radarRankScore() {
            BigDecimal hotBonus = BigDecimal.valueOf((inHotStock ? 2 : 0) + (inSuperStrong ? 4 : 0) + (limitRisk ? 3 : 0));
            return score.add(hotBonus).setScale(4, RoundingMode.HALF_UP);
        }
        boolean hasBasicThemeVolume() {
            return radarTheme != null && !limitRisk && !inFinalCandidate && turnoverYi.compareTo(new BigDecimal("10")) >= 0;
        }
        private static BigDecimal bd(JsonNode n, String field) { return n.has(field) && n.get(field).isNumber() ? n.get(field).decimalValue() : BigDecimal.ZERO; }
        private static BigDecimal max(BigDecimal a, BigDecimal b) { return a.compareTo(b) >= 0 ? a : b; }
    }

    private record PersistedSignalEvidence(boolean inUniverse, boolean inHotStock, boolean inFinalCandidate, String originalTheme) {
        static PersistedSignalEvidence empty() { return new PersistedSignalEvidence(false, false, false, ""); }
    }
}
