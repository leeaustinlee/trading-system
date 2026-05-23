package com.austin.trading.service;

import com.austin.trading.dto.response.ThemeLeadershipItemResponse;
import com.austin.trading.dto.response.ThemeLeadershipSnapshotResponse;
import com.austin.trading.entity.ThemeLeadershipSnapshotEntity;
import com.austin.trading.repository.ThemeLeadershipSnapshotRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only / observability theme leadership snapshot service.
 *
 * Safety contract: this service writes only diagnostic snapshot rows and is not
 * injected into FinalDecisionEngine or production candidate ranking.
 */
@Service
public class ThemeLeadershipObservabilityService {

    public static final String SAFETY_NOTE = "READ_ONLY_OBSERVABILITY_ONLY: diagnostics only; does not affect BUY/SELL/ENTER, FinalDecisionEngine, or production candidate ranking.";

    private final ThemeLeadershipSnapshotRepository repository;
    private final ObjectMapper objectMapper;

    public ThemeLeadershipObservabilityService(ThemeLeadershipSnapshotRepository repository,
                                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ThemeLeadershipSnapshotResponse generateSnapshot(LocalDate tradingDate, String sourcePhase, JsonNode scanPayload) {
        LocalDate date = tradingDate != null ? tradingDate : LocalDate.now();
        String phase = normalizePhase(sourcePhase);
        List<String> strongThemeCategories = readStrongThemeCategories(scanPayload);
        Map<String, MutableLeader> leaders = new LinkedHashMap<>();

        readArray(scanPayload, "hot_stocks", true, leaders);
        readArray(scanPayload, "super_strong_5", false, leaders);

        List<ThemeLeadershipSnapshotEntity> entities = new ArrayList<>();
        int rank = 1;
        for (MutableLeader leader : leaders.values()) {
            leader.leaderRank = rank++;
            applyTaxonomyAndDivergence(leader, strongThemeCategories);
            entities.add(toEntity(date, phase, leader));
        }

        repository.deleteByTradingDateAndSourcePhase(date, phase);
        repository.saveAll(entities);
        return buildResponse(date, phase, entities);
    }

    public ThemeLeadershipSnapshotResponse getSnapshot(LocalDate tradingDate, String sourcePhase, int limit) {
        LocalDate date = tradingDate != null ? tradingDate : LocalDate.now();
        String phase = sourcePhase == null || sourcePhase.isBlank() ? null : normalizePhase(sourcePhase);
        List<ThemeLeadershipSnapshotEntity> rows = phase == null
                ? repository.findByTradingDateOrderByLeaderRankAsc(date)
                : repository.findByTradingDateAndSourcePhaseOrderByLeaderRankAsc(date, phase);
        return buildResponse(date, phase, applyLimit(rows, limit));
    }

    public ThemeLeadershipSnapshotResponse getDivergence(LocalDate tradingDate, int limit) {
        LocalDate date = tradingDate != null ? tradingDate : LocalDate.now();
        List<ThemeLeadershipSnapshotEntity> rows = repository.findByTradingDateOrderByLeaderRankAsc(date).stream()
                .filter(row -> row.getDivergenceFlagsJson() != null)
                .filter(row -> !row.getDivergenceFlagsJson().isBlank())
                .filter(row -> !"[]".equals(row.getDivergenceFlagsJson().trim()))
                .toList();
        return buildResponse(date, null, applyLimit(rows, limit));
    }

    private void readArray(JsonNode root, String field, boolean hotStocks, Map<String, MutableLeader> leaders) {
        if (root == null || !root.has(field) || !root.get(field).isArray()) return;
        int rank = 1;
        for (JsonNode node : root.get(field)) {
            String symbol = text(node, "Code", "code", "symbol", "Symbol");
            if (symbol == null || symbol.isBlank()) continue;
            MutableLeader leader = leaders.computeIfAbsent(symbol.trim(), MutableLeader::new);
            leader.stockName = firstNonBlank(leader.stockName, text(node, "Name", "name", "stockName"));
            leader.themeTag = firstNonBlank(leader.themeTag, text(node, "Theme", "theme", "themeTag"));
            leader.priceChangePct = firstNonNull(leader.priceChangePct, decimal(node, "ChangePct", "changePct", "priceChangePct"));
            leader.turnover = firstNonNull(leader.turnover, decimal(node, "AmountYi", "amountYi", "turnover"));
            leader.score = firstNonNull(leader.score, decimal(node, "Score", "score"));
            leader.closeNearHigh = firstNonNull(leader.closeNearHigh, bool(node, "NearHigh", "nearHigh", "closeNearHigh"));
            leader.tradable = firstNonNull(leader.tradable, bool(node, "tradable", "Tradable", "IsBoardLotAffordable", "isBoardLotAffordable"));
            leader.tradableReason = firstNonBlank(leader.tradableReason, text(node, "TradabilityTag", "tradabilityTag", "tradableReason"));
            leader.retentionReason = firstNonBlank(leader.retentionReason, text(node, "RetentionReason", "retentionReason"));
            leader.payload = node;
            if (hotStocks && leader.hotStockRank == null) leader.hotStockRank = rank;
            if (!hotStocks && leader.superStrongRank == null) leader.superStrongRank = rank;
            rank++;
        }
    }

    private List<String> readStrongThemeCategories(JsonNode root) {
        if (root == null || !root.has("strong_themes") || !root.get("strong_themes").isArray()) return List.of();
        List<String> categories = new ArrayList<>();
        for (JsonNode node : root.get("strong_themes")) {
            String theme = text(node, "Theme", "theme", "themeTag");
            String category = ThemeTaxonomyClassifier.classify(theme);
            if (category != null && !categories.contains(category)) categories.add(category);
        }
        return categories;
    }

    private void applyTaxonomyAndDivergence(MutableLeader leader, List<String> strongThemeCategories) {
        String originalCategory = ThemeTaxonomyClassifier.classify(leader.themeTag);
        String suggested = null;
        String subTheme = null;
        if (isGenericOther(leader.themeTag) || ThemeTaxonomyClassifier.OTHER.equals(originalCategory) || ThemeTaxonomyClassifier.UNKNOWN.equals(originalCategory)) {
            suggested = ThemeTaxonomyClassifier.suggestCategoryForGenericOther(leader.symbol, leader.stockName);
            subTheme = ThemeTaxonomyClassifier.suggestSubThemeForGenericOther(leader.symbol, leader.stockName);
        }
        leader.themeCategory = usableSuggestion(suggested) ? suggested : originalCategory;
        leader.subTheme = subTheme;
        leader.taxonomyStatus = taxonomyStatus(originalCategory, leader.themeCategory);
        if (leader.hotStockRank != null && leader.hotStockRank <= 10 && !strongThemeCategories.contains(leader.themeCategory)) {
            leader.divergenceFlags.add("HOT_STOCK_TOP10_MISSING_FROM_STRONG_THEMES");
        }
        if (isGenericOther(leader.themeTag) && usableSuggestion(suggested)) {
            leader.divergenceFlags.add("OTHER_STRONG_LEADER_RECLASSIFIED");
        }
    }

    private boolean usableSuggestion(String suggested) {
        return suggested != null
                && !suggested.isBlank()
                && !ThemeTaxonomyClassifier.UNRESOLVED_OTHER.equals(suggested)
                && !ThemeTaxonomyClassifier.UNKNOWN.equals(suggested);
    }

    private String taxonomyStatus(String originalCategory, String resolvedCategory) {
        if (Objects.equals(originalCategory, resolvedCategory)) return "OK";
        if (ThemeTaxonomyClassifier.UNKNOWN.equals(originalCategory)
                || ThemeTaxonomyClassifier.OTHER.equals(originalCategory)
                || ThemeTaxonomyClassifier.UNRESOLVED_OTHER.equals(originalCategory)) {
            return "TAXONOMY_GAP";
        }
        return "RECLASSIFIED_DIAGNOSTIC";
    }

    private ThemeLeadershipSnapshotEntity toEntity(LocalDate date, String phase, MutableLeader leader) {
        ThemeLeadershipSnapshotEntity entity = new ThemeLeadershipSnapshotEntity();
        entity.setTradingDate(date);
        entity.setSourcePhase(phase);
        entity.setSymbol(leader.symbol);
        entity.setStockName(leader.stockName);
        entity.setThemeTag(leader.themeTag);
        entity.setThemeCategory(leader.themeCategory);
        entity.setSubTheme(leader.subTheme);
        entity.setLeaderRank(leader.leaderRank);
        entity.setHotStockRank(leader.hotStockRank);
        entity.setSuperStrongRank(leader.superStrongRank);
        entity.setPriceChangePct(leader.priceChangePct);
        entity.setTurnover(leader.turnover);
        entity.setScore(leader.score);
        entity.setCloseNearHigh(leader.closeNearHigh);
        entity.setTradable(leader.tradable);
        entity.setTradableReason(leader.tradableReason);
        entity.setRetentionReason(leader.retentionReason);
        entity.setTaxonomyStatus(leader.taxonomyStatus);
        entity.setDivergenceFlagsJson(writeJson(leader.divergenceFlags));
        entity.setPayloadJson(writeJson(leader.payload));
        return entity;
    }

    private ThemeLeadershipSnapshotResponse buildResponse(LocalDate date, String phase, List<ThemeLeadershipSnapshotEntity> rows) {
        List<ThemeLeadershipItemResponse> items = rows.stream()
                .sorted(Comparator.comparing(e -> e.getLeaderRank() == null ? Integer.MAX_VALUE : e.getLeaderRank()))
                .map(this::toItem)
                .toList();
        Map<String, Long> byCategory = items.stream()
                .collect(Collectors.groupingBy(i -> nullToUnknown(i.themeCategory()), LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> bySubTheme = items.stream()
                .filter(i -> i.subTheme() != null && !i.subTheme().isBlank())
                .collect(Collectors.groupingBy(ThemeLeadershipItemResponse::subTheme, LinkedHashMap::new, Collectors.counting()));
        int divergenceCount = (int) items.stream().filter(i -> !i.divergenceFlags().isEmpty()).count();
        return new ThemeLeadershipSnapshotResponse(
                date,
                phase,
                items.size(),
                divergenceCount,
                byCategory,
                bySubTheme,
                SAFETY_NOTE,
                LocalDateTime.now(),
                items
        );
    }

    private ThemeLeadershipItemResponse toItem(ThemeLeadershipSnapshotEntity e) {
        return new ThemeLeadershipItemResponse(
                e.getSymbol(), e.getStockName(), e.getThemeTag(), e.getThemeCategory(), e.getSubTheme(),
                e.getLeaderRank(), e.getHotStockRank(), e.getSuperStrongRank(),
                e.getPriceChangePct(), e.getTurnover(), e.getScore(), e.getCloseNearHigh(),
                e.getTradable(), e.getTradableReason(), e.getRetentionReason(), e.getTaxonomyStatus(),
                readStringList(e.getDivergenceFlagsJson())
        );
    }

    private List<ThemeLeadershipSnapshotEntity> applyLimit(List<ThemeLeadershipSnapshotEntity> rows, int limit) {
        int safeLimit = limit <= 0 ? 50 : Math.min(limit, 500);
        return rows.stream().limit(safeLimit).toList();
    }

    private String writeJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) return List.of();
            List<String> result = new ArrayList<>();
            for (JsonNode item : node) {
                if (item.isTextual()) result.add(item.asText());
            }
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String normalizePhase(String sourcePhase) {
        if (sourcePhase == null || sourcePhase.isBlank()) return "POSTMARKET";
        return sourcePhase.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isGenericOther(String themeTag) {
        if (themeTag == null) return false;
        String normalized = themeTag.trim().toUpperCase(Locale.ROOT);
        return normalized.contains("其他") || normalized.equals("OTHER") || normalized.contains("OTHER");
    }

    private String text(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.has(field) && !node.get(field).isNull()) {
                String value = node.get(field).asText(null);
                if (value != null && !value.isBlank()) return value.trim();
            }
        }
        return null;
    }

    private BigDecimal decimal(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.has(field) && node.get(field).isNumber()) return node.get(field).decimalValue();
            if (node.has(field) && node.get(field).isTextual()) {
                try {
                    return new BigDecimal(node.get(field).asText().trim());
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private Boolean bool(JsonNode node, String... fields) {
        Set<String> trueValues = Set.of("1", "true", "yes", "y");
        Set<String> falseValues = Set.of("0", "false", "no", "n");
        for (String field : fields) {
            if (!node.has(field) || node.get(field).isNull()) continue;
            JsonNode value = node.get(field);
            if (value.isBoolean()) return value.asBoolean();
            if (value.isNumber()) return value.asInt() != 0;
            if (value.isTextual()) {
                String text = value.asText().trim().toLowerCase(Locale.ROOT);
                if (trueValues.contains(text)) return true;
                if (falseValues.contains(text)) return false;
            }
        }
        return null;
    }

    private String firstNonBlank(String existing, String candidate) {
        if (existing != null && !existing.isBlank()) return existing;
        return candidate;
    }

    private <T> T firstNonNull(T existing, T candidate) {
        return existing != null ? existing : candidate;
    }

    private String nullToUnknown(String value) {
        return value == null || value.isBlank() ? ThemeTaxonomyClassifier.UNKNOWN : value;
    }

    private static class MutableLeader {
        private final String symbol;
        private String stockName;
        private String themeTag;
        private String themeCategory;
        private String subTheme;
        private Integer leaderRank;
        private Integer hotStockRank;
        private Integer superStrongRank;
        private BigDecimal priceChangePct;
        private BigDecimal turnover;
        private BigDecimal score;
        private Boolean closeNearHigh;
        private Boolean tradable;
        private String tradableReason;
        private String retentionReason;
        private String taxonomyStatus;
        private JsonNode payload;
        private final List<String> divergenceFlags = new ArrayList<>();

        private MutableLeader(String symbol) {
            this.symbol = symbol;
        }
    }
}
