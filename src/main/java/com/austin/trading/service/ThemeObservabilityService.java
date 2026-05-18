package com.austin.trading.service;

import com.austin.trading.dto.response.StockThemeMappingResponse;
import com.austin.trading.dto.response.ThemeMappingIssueResponse;
import com.austin.trading.dto.response.ThemeMappingObservabilityResponse;
import com.austin.trading.dto.response.ThemeOtherCategorySuggestionResponse;
import com.austin.trading.dto.response.ThemeTaxonomyItemResponse;
import com.austin.trading.dto.response.ThemeTaxonomyResponse;
import com.austin.trading.entity.StockThemeMappingEntity;
import com.austin.trading.entity.ThemeSnapshotEntity;
import com.austin.trading.repository.StockThemeMappingRepository;
import com.austin.trading.repository.ThemeSnapshotRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only Theme Taxonomy / Mapping observability layer.
 *
 * Safety contract: this service must not write theme mappings, snapshots,
 * candidate rows, final decisions, or any BUY/SELL path state. It only derives
 * diagnostics from existing theme tables so shadow/trace data is not mistaken
 * for live trading decisions.
 */
@Service
public class ThemeObservabilityService {

    static final String SAFETY_NOTE = "READ_ONLY_OBSERVABILITY_ONLY: does not change BUY/SELL/FinalDecision semantics";
    static final BigDecimal DEFAULT_LOW_CONFIDENCE_THRESHOLD = new BigDecimal("0.70");

    private final StockThemeMappingRepository mappingRepo;
    private final ThemeSnapshotRepository snapshotRepo;

    public ThemeObservabilityService(StockThemeMappingRepository mappingRepo,
                                     ThemeSnapshotRepository snapshotRepo) {
        this.mappingRepo = mappingRepo;
        this.snapshotRepo = snapshotRepo;
    }

    public ThemeTaxonomyResponse getTaxonomy(LocalDate tradingDate, boolean activeOnly) {
        LocalDate date = tradingDate != null ? tradingDate : LocalDate.now();
        List<StockThemeMappingEntity> mappings = loadMappings(activeOnly);
        Map<String, ThemeSnapshotEntity> snapshotsByTheme = snapshotRepo.findByTradingDateOrderByFinalThemeScoreDesc(date)
                .stream()
                .filter(s -> hasText(s.getThemeTag()))
                .collect(Collectors.toMap(
                        s -> normalizeKey(s.getThemeTag()),
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        Map<String, List<StockThemeMappingEntity>> mappingsByTheme = mappings.stream()
                .filter(m -> hasText(m.getThemeTag()))
                .collect(Collectors.groupingBy(
                        m -> normalizeKey(m.getThemeTag()),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        LinkedHashMap<String, String> displayTags = new LinkedHashMap<>();
        mappings.forEach(m -> {
            if (hasText(m.getThemeTag())) displayTags.putIfAbsent(normalizeKey(m.getThemeTag()), m.getThemeTag().trim());
        });
        snapshotsByTheme.values().forEach(s -> displayTags.putIfAbsent(normalizeKey(s.getThemeTag()), s.getThemeTag().trim()));

        List<ThemeTaxonomyItemResponse> items = displayTags.entrySet().stream()
                .map(entry -> buildTaxonomyItem(entry.getKey(), entry.getValue(), mappingsByTheme.getOrDefault(entry.getKey(), List.of()), snapshotsByTheme.get(entry.getKey())))
                .sorted(Comparator
                        .comparing((ThemeTaxonomyItemResponse i) -> Optional.ofNullable(i.rankingOrder()).orElse(Integer.MAX_VALUE))
                        .thenComparing(i -> Optional.ofNullable(i.themeTag()).orElse("")))
                .toList();

        return new ThemeTaxonomyResponse(date, items.size(), activeOnly, SAFETY_NOTE, LocalDateTime.now(), items);
    }

    public ThemeMappingObservabilityResponse getMappingObservability(String symbol,
                                                                     String theme,
                                                                     String category,
                                                                     String source,
                                                                     Boolean activeOnly,
                                                                     BigDecimal minConfidence,
                                                                     Integer limit,
                                                                     String suggestedCategory,
                                                                     Boolean unresolvedOtherOnly) {
        return getMappingObservability(symbol, theme, category, source, activeOnly, minConfidence, limit,
                suggestedCategory, unresolvedOtherOnly, null, null);
    }

    public ThemeMappingObservabilityResponse getMappingObservability(String symbol,
                                                                     String theme,
                                                                     String category,
                                                                     String source,
                                                                     Boolean activeOnly,
                                                                     BigDecimal minConfidence,
                                                                     Integer limit,
                                                                     String suggestedCategory,
                                                                     Boolean unresolvedOtherOnly,
                                                                     String reviewPriority,
                                                                     String recommendedAction) {
        boolean onlyActive = activeOnly == null || activeOnly;
        BigDecimal threshold = minConfidence != null ? minConfidence : DEFAULT_LOW_CONFIDENCE_THRESHOLD;
        int safeLimit = limit == null ? 200 : Math.max(1, Math.min(limit, 1_000));

        List<StockThemeMappingEntity> all = mappingRepo.findAllByOrderBySymbolAscThemeTagAsc();
        boolean onlyUnresolvedOther = Boolean.TRUE.equals(unresolvedOtherOnly);
        List<StockThemeMappingEntity> filtered = all.stream()
                .filter(m -> !onlyActive || Boolean.TRUE.equals(m.getIsActive()))
                .filter(m -> !hasText(symbol) || equalsIgnoreCase(m.getSymbol(), symbol))
                .filter(m -> !hasText(theme) || equalsIgnoreCase(m.getThemeTag(), theme))
                .filter(m -> !hasText(category) || equalsIgnoreCase(m.getThemeCategory(), category))
                .filter(m -> !hasText(source) || equalsIgnoreCase(m.getSource(), source))
                .filter(m -> !hasText(suggestedCategory) || (isOtherCategory(m) && equalsIgnoreCase(suggestedCategory(m), suggestedCategory)))
                .filter(m -> !onlyUnresolvedOther || isUnresolvedOtherSuggestion(m))
                .filter(m -> !hasText(reviewPriority) || (isOtherCategory(m) && equalsIgnoreCase(reviewPriority(m, isUnresolvedOtherSuggestion(m)), reviewPriority)))
                .filter(m -> !hasText(recommendedAction) || (isOtherCategory(m) && equalsIgnoreCase(recommendedAction(isUnresolvedOtherSuggestion(m)), recommendedAction)))
                .toList();

        long activeMappings = filtered.stream().filter(m -> Boolean.TRUE.equals(m.getIsActive())).count();
        long inactiveMappings = filtered.size() - activeMappings;
        long distinctSymbols = filtered.stream().map(StockThemeMappingEntity::getSymbol).filter(ThemeObservabilityService::hasText).map(ThemeObservabilityService::normalizeKey).distinct().count();
        long distinctThemes = filtered.stream().map(StockThemeMappingEntity::getThemeTag).filter(ThemeObservabilityService::hasText).map(ThemeObservabilityService::normalizeKey).distinct().count();
        Map<String, Long> byCategory = countBy(filtered, StockThemeMappingEntity::getThemeCategory, "UNCATEGORIZED");
        Map<String, Long> bySource = countBy(filtered, StockThemeMappingEntity::getSource, "UNKNOWN_SOURCE");

        Set<String> ambiguousSymbols = filtered.stream()
                .filter(m -> Boolean.TRUE.equals(m.getIsActive()))
                .filter(m -> hasText(m.getSymbol()))
                .collect(Collectors.groupingBy(m -> normalizeKey(m.getSymbol()), Collectors.mapping(m -> normalizeKey(m.getThemeTag()), Collectors.toSet())))
                .entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        List<ThemeMappingIssueResponse> allIssues = buildIssues(filtered, threshold, ambiguousSymbols);
        Map<String, Long> byIssueType = countIssuesByType(allIssues);
        List<ThemeMappingIssueResponse> issues = allIssues.stream()
                .limit(safeLimit)
                .toList();

        List<StockThemeMappingResponse> mappings = filtered.stream()
                .limit(safeLimit)
                .map(this::toMappingResponse)
                .toList();

        long missingCategoryCount = filtered.stream().filter(m -> !hasText(m.getThemeCategory())).count();
        long missingSourceCount = filtered.stream().filter(m -> !hasText(m.getSource())).count();
        long missingConfidenceCount = filtered.stream().filter(m -> m.getConfidence() == null).count();
        long lowConfidenceCount = filtered.stream().filter(m -> isLowConfidence(m, threshold)).count();
        long otherCategoryCount = filtered.stream().filter(ThemeObservabilityService::isOtherCategory).count();
        BigDecimal otherCategoryRatio = ratio(otherCategoryCount, filtered.size());
        List<StockThemeMappingEntity> otherRows = filtered.stream()
                .filter(ThemeObservabilityService::isOtherCategory)
                .toList();
        Map<String, Long> otherBySuggestedCategory = otherRows.stream()
                .map(ThemeObservabilityService::suggestedCategory)
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
        long unresolvedOtherCategoryCount = otherBySuggestedCategory.getOrDefault(ThemeTaxonomyClassifier.UNRESOLVED_OTHER, 0L);
        long resolvableOtherCategoryCount = otherCategoryCount - unresolvedOtherCategoryCount;
        List<String> qualityWarnings = buildQualityWarnings(
                missingCategoryCount,
                missingSourceCount,
                missingConfidenceCount,
                lowConfidenceCount,
                ambiguousSymbols.size(),
                resolvableOtherCategoryCount,
                unresolvedOtherCategoryCount
        );
        String taxonomyQualityStatus = taxonomyQualityStatus(
                missingCategoryCount,
                missingSourceCount,
                missingConfidenceCount,
                lowConfidenceCount,
                ambiguousSymbols.size(),
                resolvableOtherCategoryCount,
                unresolvedOtherCategoryCount
        );
        String taxonomyQualitySummary = taxonomyQualitySummary(taxonomyQualityStatus, qualityWarnings);
        List<ThemeOtherCategorySuggestionResponse> otherCategorySuggestions = otherRows.stream()
                .limit(safeLimit)
                .map(this::toOtherCategorySuggestion)
                .toList();

        return new ThemeMappingObservabilityResponse(
                filtered.size(),
                activeMappings,
                inactiveMappings,
                distinctSymbols,
                distinctThemes,
                byCategory,
                bySource,
                missingCategoryCount,
                missingSourceCount,
                missingConfidenceCount,
                lowConfidenceCount,
                ambiguousSymbols.size(),
                otherCategoryCount,
                otherCategoryRatio,
                otherBySuggestedCategory,
                resolvableOtherCategoryCount,
                unresolvedOtherCategoryCount,
                threshold,
                byIssueType,
                taxonomyQualityStatus,
                taxonomyQualitySummary,
                qualityWarnings,
                SAFETY_NOTE,
                LocalDateTime.now(),
                issues,
                otherCategorySuggestions,
                mappings
        );
    }

    private List<StockThemeMappingEntity> loadMappings(boolean activeOnly) {
        return mappingRepo.findAllByOrderBySymbolAscThemeTagAsc().stream()
                .filter(m -> !activeOnly || Boolean.TRUE.equals(m.getIsActive()))
                .toList();
    }

    private ThemeTaxonomyItemResponse buildTaxonomyItem(String normalizedTheme,
                                                       String displayTheme,
                                                       List<StockThemeMappingEntity> mappings,
                                                       ThemeSnapshotEntity snapshot) {
        String category = firstText(
                mappings.stream().map(StockThemeMappingEntity::getThemeCategory).toList(),
                snapshot != null ? snapshot.getThemeCategory() : null,
                "UNCATEGORIZED"
        );
        List<String> subThemes = mappings.stream()
                .map(StockThemeMappingEntity::getSubTheme)
                .filter(ThemeObservabilityService::hasText)
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
        long activeStockCount = mappings.stream()
                .filter(m -> Boolean.TRUE.equals(m.getIsActive()))
                .map(StockThemeMappingEntity::getSymbol)
                .filter(ThemeObservabilityService::hasText)
                .map(ThemeObservabilityService::normalizeKey)
                .distinct()
                .count();
        Map<String, Long> sources = countBy(mappings, StockThemeMappingEntity::getSource, "UNKNOWN_SOURCE");
        BigDecimal avgConfidence = avgConfidence(mappings);

        return new ThemeTaxonomyItemResponse(
                hasText(displayTheme) ? displayTheme : normalizedTheme,
                category,
                subThemes,
                activeStockCount,
                sources,
                avgConfidence,
                snapshot != null ? snapshot.getFinalThemeScore() : null,
                snapshot != null ? snapshot.getMarketBehaviorScore() : null,
                snapshot != null ? snapshot.getThemeHeatScore() : null,
                snapshot != null ? snapshot.getThemeContinuationScore() : null,
                snapshot != null ? snapshot.getRankingOrder() : null,
                snapshot != null ? snapshot.getLeadingStockSymbol() : null
        );
    }

    private List<ThemeMappingIssueResponse> buildIssues(List<StockThemeMappingEntity> mappings,
                                                        BigDecimal threshold,
                                                        Set<String> ambiguousSymbols) {
        List<ThemeMappingIssueResponse> issues = new ArrayList<>();
        for (StockThemeMappingEntity m : mappings) {
            if (!hasText(m.getThemeCategory())) issues.add(toIssue(m, "MISSING_CATEGORY", "theme_category is blank"));
            if (!hasText(m.getSource())) issues.add(toIssue(m, "MISSING_SOURCE", "source is blank"));
            if (m.getConfidence() == null) issues.add(toIssue(m, "MISSING_CONFIDENCE", "confidence is null"));
            if (isLowConfidence(m, threshold)) issues.add(toIssue(m, "LOW_CONFIDENCE", "confidence below " + threshold));
            if (isOtherCategory(m)) issues.add(toIssue(m, "OTHER_CATEGORY_REVIEW", "theme_category=OTHER; inspect whether a more specific taxonomy label exists"));
            if (hasText(m.getSymbol()) && ambiguousSymbols.contains(normalizeKey(m.getSymbol()))) {
                issues.add(toIssue(m, "AMBIGUOUS_SYMBOL", "active symbol maps to multiple themes; inspect whether this is intended"));
            }
        }
        return issues;
    }

    private ThemeMappingIssueResponse toIssue(StockThemeMappingEntity e, String type, String detail) {
        return new ThemeMappingIssueResponse(
                e.getId(), e.getSymbol(), e.getStockName(), e.getThemeTag(), e.getThemeCategory(),
                e.getSource(), e.getConfidence(), Boolean.TRUE.equals(e.getIsActive()), type, detail
        );
    }

    private ThemeOtherCategorySuggestionResponse toOtherCategorySuggestion(StockThemeMappingEntity e) {
        String suggested = suggestedCategory(e);
        boolean unresolved = ThemeTaxonomyClassifier.UNRESOLVED_OTHER.equals(suggested);
        return new ThemeOtherCategorySuggestionResponse(
                e.getId(), e.getSymbol(), e.getStockName(), e.getThemeTag(), e.getThemeCategory(),
                suggested,
                unresolved
                        ? "no deterministic stock-name rule matched; keep in OTHER review queue"
                        : "deterministic stock-name review suggestion only; stored category remains unchanged",
                reviewPriority(e, unresolved),
                recommendedAction(unresolved),
                unresolved
                        ? "review stock business/domain externally, then assign a specific taxonomy category or intentionally keep OTHER"
                        : "deterministic suggestion is available; verify before any write/backfill step",
                e.getSource(), e.getConfidence(), Boolean.TRUE.equals(e.getIsActive())
        );
    }

    private StockThemeMappingResponse toMappingResponse(StockThemeMappingEntity e) {
        return new StockThemeMappingResponse(
                e.getId(), e.getSymbol(), e.getStockName(),
                e.getThemeTag(), e.getSubTheme(), e.getThemeCategory(),
                e.getSource(), e.getConfidence(), e.getIsActive(),
                e.getCreatedAt(), e.getUpdatedAt()
        );
    }

    private static String reviewPriority(StockThemeMappingEntity mapping, boolean unresolved) {
        if (!Boolean.TRUE.equals(mapping.getIsActive())) return "LOW";
        if (unresolved && mapping.getConfidence() != null && mapping.getConfidence().compareTo(DEFAULT_LOW_CONFIDENCE_THRESHOLD) >= 0) {
            return "HIGH";
        }
        return unresolved ? "MEDIUM" : "LOW";
    }

    private static String recommendedAction(boolean unresolved) {
        return unresolved ? "MANUAL_CLASSIFICATION_REQUIRED" : "REVIEW_AND_APPLY_DETERMINISTIC_CATEGORY";
    }

    private static Map<String, Long> countBy(List<StockThemeMappingEntity> mappings,
                                             Function<StockThemeMappingEntity, String> extractor,
                                             String fallback) {
        return mappings.stream()
                .map(extractor)
                .map(v -> hasText(v) ? v.trim() : fallback)
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
    }

    private static Map<String, Long> countIssuesByType(List<ThemeMappingIssueResponse> issues) {
        return issues.stream()
                .map(ThemeMappingIssueResponse::issueType)
                .filter(ThemeObservabilityService::hasText)
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
    }

    private static List<String> buildQualityWarnings(long missingCategoryCount,
                                                     long missingSourceCount,
                                                     long missingConfidenceCount,
                                                     long lowConfidenceCount,
                                                     long ambiguousSymbolCount,
                                                     long resolvableOtherCategoryCount,
                                                     long unresolvedOtherCategoryCount) {
        List<String> warnings = new ArrayList<>();
        if (missingCategoryCount > 0) warnings.add("MISSING_CATEGORY=" + missingCategoryCount);
        if (missingSourceCount > 0) warnings.add("MISSING_SOURCE=" + missingSourceCount);
        if (missingConfidenceCount > 0) warnings.add("MISSING_CONFIDENCE=" + missingConfidenceCount);
        if (lowConfidenceCount > 0) warnings.add("LOW_CONFIDENCE=" + lowConfidenceCount);
        if (ambiguousSymbolCount > 0) warnings.add("AMBIGUOUS_SYMBOL=" + ambiguousSymbolCount);
        if (resolvableOtherCategoryCount > 0) warnings.add("RESOLVABLE_OTHER=" + resolvableOtherCategoryCount);
        if (unresolvedOtherCategoryCount > 0) warnings.add("UNRESOLVED_OTHER_MANUAL_REVIEW=" + unresolvedOtherCategoryCount);
        return warnings;
    }

    private static String taxonomyQualityStatus(long missingCategoryCount,
                                                long missingSourceCount,
                                                long missingConfidenceCount,
                                                long lowConfidenceCount,
                                                long ambiguousSymbolCount,
                                                long resolvableOtherCategoryCount,
                                                long unresolvedOtherCategoryCount) {
        if (missingCategoryCount > 0 || missingSourceCount > 0 || missingConfidenceCount > 0) {
            return "DATA_GAP";
        }
        if (resolvableOtherCategoryCount > 0) {
            return "REFINEMENT_READY";
        }
        if (lowConfidenceCount > 0 || ambiguousSymbolCount > 0) {
            return "REVIEW_REQUIRED";
        }
        if (unresolvedOtherCategoryCount > 0) {
            return "MANUAL_REVIEW";
        }
        return "OK";
    }

    private static String taxonomyQualitySummary(String status, List<String> warnings) {
        return switch (status) {
            case "DATA_GAP" -> "taxonomy mapping has required metadata gaps before it can be treated as complete";
            case "REFINEMENT_READY" -> "deterministic OTHER refinements are available; run reviewed backfill before relying on category concentration";
            case "REVIEW_REQUIRED" -> "taxonomy mapping is populated but still has low-confidence or ambiguous rows to review";
            case "MANUAL_REVIEW" -> "taxonomy mapping is mostly normalized; only unresolved OTHER rows remain for manual review";
            default -> warnings.isEmpty()
                    ? "taxonomy mapping quality checks are clean for the current filter"
                    : "taxonomy mapping quality checks reported: " + String.join(", ", warnings);
        };
    }

    private static BigDecimal avgConfidence(List<StockThemeMappingEntity> mappings) {
        List<BigDecimal> values = mappings.stream()
                .map(StockThemeMappingEntity::getConfidence)
                .filter(Objects::nonNull)
                .toList();
        if (values.isEmpty()) return null;
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }

    private static boolean isLowConfidence(StockThemeMappingEntity mapping, BigDecimal threshold) {
        return mapping.getConfidence() != null && mapping.getConfidence().compareTo(threshold) < 0;
    }

    private static boolean isOtherCategory(StockThemeMappingEntity mapping) {
        return equalsIgnoreCase(mapping.getThemeCategory(), ThemeTaxonomyClassifier.OTHER);
    }

    private static String suggestedCategory(StockThemeMappingEntity mapping) {
        return ThemeTaxonomyClassifier.suggestCategoryForGenericOther(mapping.getSymbol(), mapping.getStockName());
    }

    private static boolean isUnresolvedOtherSuggestion(StockThemeMappingEntity mapping) {
        return isOtherCategory(mapping) && ThemeTaxonomyClassifier.UNRESOLVED_OTHER.equals(suggestedCategory(mapping));
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        if (denominator <= 0) return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private static String firstText(List<String> values, String fallbackValue, String finalFallback) {
        return values.stream().filter(ThemeObservabilityService::hasText).map(String::trim).findFirst()
                .orElse(hasText(fallbackValue) ? fallbackValue.trim() : finalFallback);
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return hasText(left) && hasText(right) && left.trim().equalsIgnoreCase(right.trim());
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
