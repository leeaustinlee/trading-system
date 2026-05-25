package com.austin.trading.service;

import com.austin.trading.dto.response.NarrativeShadowReviewResponse;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.KolThemeSignalDailySnapshotEntity;
import com.austin.trading.entity.NarrativeCandidateTrackingSeedEntity;
import com.austin.trading.entity.NarrativeShadowReviewEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.KolThemeSignalDailySnapshotRepository;
import com.austin.trading.repository.NarrativeCandidateTrackingSeedRepository;
import com.austin.trading.repository.NarrativeShadowReviewRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class NarrativeShadowReviewService {
    private static final String GUARDRAIL = KolSignalContextService.WEAK_SIGNAL_GUARDRAIL
            + " Narrative Shadow Review is observation-only and cannot become BUY/SELL/ENTER or override hard gates.";

    private final KolThemeSignalDailySnapshotRepository snapshotRepo;
    private final CandidateStockRepository candidateRepo;
    private final NarrativeShadowReviewRepository reviewRepo;
    private final NarrativeCandidateTrackingSeedRepository seedRepo;
    private final ObjectMapper objectMapper;
    private final NarrativeShadowObservationMetrics observationMetrics;

    public NarrativeShadowReviewService(KolThemeSignalDailySnapshotRepository snapshotRepo,
                                        CandidateStockRepository candidateRepo,
                                        NarrativeShadowReviewRepository reviewRepo,
                                        NarrativeCandidateTrackingSeedRepository seedRepo,
                                        ObjectMapper objectMapper,
                                        NarrativeShadowObservationMetrics observationMetrics) {
        this.snapshotRepo = snapshotRepo;
        this.candidateRepo = candidateRepo;
        this.reviewRepo = reviewRepo;
        this.seedRepo = seedRepo;
        this.objectMapper = objectMapper;
        this.observationMetrics = observationMetrics;
    }

    @Transactional(readOnly = true)
    public NarrativeShadowReviewResponse report(LocalDate date) {
        return reviewRepo.findByTradingDate(date)
                .map(review -> fromPersisted(review, seedRepo.findByDecisionDateOrderByShadowDeltaDesc(date)))
                .orElseGet(() -> aggregatePreview(date));
    }

    @Transactional
    public NarrativeShadowReviewResponse aggregate(LocalDate date) {
        NarrativeShadowReviewResponse response = aggregatePreview(date);
        reviewRepo.deleteByTradingDate(date);
        reviewRepo.flush();
        seedRepo.deleteByDecisionDate(date);
        seedRepo.flush();
        NarrativeShadowReviewEntity review = new NarrativeShadowReviewEntity();
        review.setTradingDate(date);
        review.setWeakSignalOnly(true);
        review.setShadowOnly(true);
        review.setObservabilityOnly(true);
        review.setProductionDecisionAllowed(false);
        review.setGuardrail(GUARDRAIL);
        review.setThemeSummaryJson(json(response.themeSummary()));
        review.setLifecycleBoardJson(json(response.lifecycleBoard()));
        review.setCandidateImpactJson(json(response.candidateImpact()));
        review.setWarningsJson(json(response.warnings()));
        review.setRotationAnalysisJson(json(response.rotationAnalysis()));
        review.setMetricsJson(json(response.metrics()));
        review.setNote(response.note());
        reviewRepo.save(review);
        seedRepo.saveAll(response.trackingSeeds().stream().map(this::toSeedEntity).toList());
        return response;
    }

    private NarrativeShadowReviewResponse aggregatePreview(LocalDate date) {
        List<KolThemeSignalDailySnapshotEntity> snapshots = snapshotRepo.findByTradingDateOrderByNetShadowBoostDesc(date);
        List<NarrativeShadowReviewResponse.ThemeSummary> themes = snapshots.stream()
                .map(this::toThemeSummary)
                .sorted(Comparator.comparing(NarrativeShadowReviewResponse.ThemeSummary::attentionScore).reversed())
                .toList();
        Map<String, NarrativeShadowReviewResponse.ThemeSummary> themeByName = themes.stream()
                .collect(Collectors.toMap(NarrativeShadowReviewResponse.ThemeSummary::theme, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<String, Long> lifecycleBoard = themes.stream()
                .collect(Collectors.groupingBy(NarrativeShadowReviewResponse.ThemeSummary::lifecycle, LinkedHashMap::new, Collectors.counting()));
        List<CandidateStockEntity> candidates = candidateRepo.findByTradingDateOrderByScoreDesc(date, Pageable.unpaged());
        List<NarrativeShadowReviewResponse.CandidateImpact> impacts = candidates.stream()
                .map(candidate -> toCandidateImpact(candidate, themeByName.get(candidate.getThemeTag())))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(NarrativeShadowReviewResponse.CandidateImpact::shadowDelta).reversed())
                .toList();
        List<String> warnings = collectWarnings(themes, impacts);
        NarrativeShadowReviewResponse.RotationAnalysis rotation = rotation(themes);
        List<NarrativeShadowReviewResponse.TrackingSeed> seeds = impacts.stream()
                .map(item -> new NarrativeShadowReviewResponse.TrackingSeed(
                        item.symbol(), item.name(), item.relatedTheme(), item.lifecycle(),
                        themeByName.get(item.relatedTheme()).attentionScore(),
                        themeByName.get(item.relatedTheme()).crowdingScore(),
                        item.shadowDelta(), date, true, false))
                .toList();
        Map<String, Long> metrics = metrics(themes, impacts, warnings, seeds);
        observationMetrics.publish(metrics);
        return new NarrativeShadowReviewResponse(date, true, true, true, false, GUARDRAIL, themes,
                lifecycleBoard, impacts, warnings, rotation, seeds, metrics,
                "persisted shadow observation only; production decisions and hard gates unchanged");
    }

    private NarrativeShadowReviewResponse.ThemeSummary toThemeSummary(KolThemeSignalDailySnapshotEntity e) {
        BigDecimal attention = tenPoint(safe(e.getPositiveScore()).max(safe(e.getNegativeScore())));
        BigDecimal crowding = crowdingScore(e.getCrowdingRisk());
        String lifecycle = lifecycle(attention, crowding, e.getSourceCount());
        return new NarrativeShadowReviewResponse.ThemeSummary(
                e.getThemeTag(), lifecycle, attention, BigDecimal.ONE.setScale(3), crowding,
                sourceDensity(e.getSourceCount()), safeInt(e.getSourceCount()), parseStringList(e.getTopSourcesJson()));
    }

    private NarrativeShadowReviewResponse.CandidateImpact toCandidateImpact(CandidateStockEntity candidate,
                                                                            NarrativeShadowReviewResponse.ThemeSummary theme) {
        if (theme == null) return null;
        BigDecimal base = safe(candidate.getScore());
        BigDecimal delta = theme.lifecycle().equals("CROWDED") || theme.lifecycle().equals("EXHAUSTED")
                ? new BigDecimal("-0.1000") : new BigDecimal("0.1000");
        if (theme.attentionScore().compareTo(new BigDecimal("8.0")) >= 0 && !theme.lifecycle().equals("CROWDED")) {
            delta = new BigDecimal("0.1800");
        }
        List<String> riskFlags = riskFlags(theme);
        List<String> crowdingFlags = crowdingFlags(theme);
        return new NarrativeShadowReviewResponse.CandidateImpact(
                candidate.getSymbol(), candidate.getStockName(), candidate.getThemeTag(), theme.lifecycle(),
                base, delta, delta.compareTo(BigDecimal.ZERO) > 0 && base.compareTo(new BigDecimal("7.5")) >= 0,
                riskFlags, crowdingFlags);
    }

    private List<String> riskFlags(NarrativeShadowReviewResponse.ThemeSummary theme) {
        List<String> flags = new ArrayList<>();
        if (theme.crowdingScore().compareTo(new BigDecimal("8.0")) >= 0 || theme.lifecycle().equals("CROWDED") || theme.lifecycle().equals("EXHAUSTED")) {
            flags.add("DO_NOT_CHASE");
            flags.add("CROWDED_THEME");
            flags.add("NEED_PULLBACK_CONFIRMATION");
        }
        return flags;
    }

    private List<String> crowdingFlags(NarrativeShadowReviewResponse.ThemeSummary theme) {
        if (theme.crowdingScore().compareTo(new BigDecimal("8.0")) >= 0 || theme.lifecycle().equals("CROWDED") || theme.lifecycle().equals("EXHAUSTED")) {
            return List.of("NARRATIVE_OVERHEATED");
        }
        return List.of();
    }

    private List<String> collectWarnings(List<NarrativeShadowReviewResponse.ThemeSummary> themes,
                                         List<NarrativeShadowReviewResponse.CandidateImpact> impacts) {
        LinkedHashSet<String> warnings = new LinkedHashSet<>();
        for (var theme : themes) {
            if (!riskFlags(theme).isEmpty()) {
                warnings.add("DO_NOT_CHASE");
                warnings.add("NARRATIVE_OVERHEATED");
                warnings.add("CROWDED_THEME");
                warnings.add("NEED_PULLBACK_CONFIRMATION");
            }
        }
        return List.copyOf(warnings);
    }

    private NarrativeShadowReviewResponse.RotationAnalysis rotation(List<NarrativeShadowReviewResponse.ThemeSummary> themes) {
        List<String> emerging = themes.stream().filter(t -> t.lifecycle().equals("EMERGING")).map(NarrativeShadowReviewResponse.ThemeSummary::theme).toList();
        List<String> expanding = themes.stream().filter(t -> t.lifecycle().equals("EXPANDING")).map(NarrativeShadowReviewResponse.ThemeSummary::theme).toList();
        List<String> crowded = themes.stream().filter(t -> t.lifecycle().equals("CROWDED")).map(NarrativeShadowReviewResponse.ThemeSummary::theme).toList();
        List<String> exhausted = themes.stream().filter(t -> t.lifecycle().equals("EXHAUSTED")).map(NarrativeShadowReviewResponse.ThemeSummary::theme).toList();
        String summary = emerging.isEmpty() && crowded.isEmpty()
                ? "no narrative rotation evidence"
                : "emerging=" + emerging + "; expanding=" + expanding + "; crowded/exhausted=" + crowded + exhausted;
        return new NarrativeShadowReviewResponse.RotationAnalysis(emerging, expanding, crowded, exhausted, summary);
    }

    private Map<String, Long> metrics(List<NarrativeShadowReviewResponse.ThemeSummary> themes,
                                      List<NarrativeShadowReviewResponse.CandidateImpact> impacts,
                                      List<String> warnings,
                                      List<NarrativeShadowReviewResponse.TrackingSeed> seeds) {
        long boost = impacts.stream().filter(i -> i.shadowDelta().compareTo(BigDecimal.ZERO) > 0).count();
        long penalty = impacts.stream().filter(i -> i.shadowDelta().compareTo(BigDecimal.ZERO) < 0).count();
        return new LinkedHashMap<>(Map.of(
                "narrative_theme_emerging_total", themes.stream().filter(t -> t.lifecycle().equals("EMERGING")).count(),
                "narrative_theme_crowded_total", themes.stream().filter(t -> t.lifecycle().equals("CROWDED") || t.lifecycle().equals("EXHAUSTED")).count(),
                "narrative_shadow_boost_total", boost,
                "narrative_shadow_penalty_total", penalty,
                "narrative_fomo_warning_total", warnings.contains("DO_NOT_CHASE") ? 1L : 0L,
                "narrative_candidate_tracking_total", (long) seeds.size()
        ));
    }

    private NarrativeCandidateTrackingSeedEntity toSeedEntity(NarrativeShadowReviewResponse.TrackingSeed seed) {
        NarrativeCandidateTrackingSeedEntity e = new NarrativeCandidateTrackingSeedEntity();
        e.setDecisionDate(seed.decisionDate());
        e.setSymbol(seed.symbol());
        e.setStockName(seed.name());
        e.setRelatedTheme(seed.relatedTheme());
        e.setLifecycleAtDetection(seed.lifecycleAtDetection());
        e.setAttentionScore(seed.attentionScore());
        e.setCrowdingScore(seed.crowdingScore());
        e.setShadowDelta(seed.shadowDelta());
        e.setWeakSignalOnly(true);
        e.setProductionDecisionAllowed(false);
        e.setPayloadJson(json(Map.of("purpose", "T+1/T+3/T+5 lead-time/FOMO validation seed")));
        return e;
    }

    private NarrativeShadowReviewResponse fromPersisted(NarrativeShadowReviewEntity review, List<NarrativeCandidateTrackingSeedEntity> seeds) {
        LocalDate date = review.getTradingDate();
        return new NarrativeShadowReviewResponse(date, true, true, true, false,
                review.getGuardrail() == null ? GUARDRAIL : review.getGuardrail(),
                read(review.getThemeSummaryJson(), new TypeReference<List<NarrativeShadowReviewResponse.ThemeSummary>>() {}, List.of()),
                read(review.getLifecycleBoardJson(), new TypeReference<Map<String, Long>>() {}, Map.of()),
                read(review.getCandidateImpactJson(), new TypeReference<List<NarrativeShadowReviewResponse.CandidateImpact>>() {}, List.of()),
                read(review.getWarningsJson(), new TypeReference<List<String>>() {}, List.of()),
                read(review.getRotationAnalysisJson(), new TypeReference<NarrativeShadowReviewResponse.RotationAnalysis>() {},
                        new NarrativeShadowReviewResponse.RotationAnalysis(List.of(), List.of(), List.of(), List.of(), "no narrative rotation evidence")),
                seeds.stream().map(this::toSeedResponse).toList(),
                read(review.getMetricsJson(), new TypeReference<Map<String, Long>>() {}, Map.of()),
                review.getNote());
    }

    private NarrativeShadowReviewResponse.TrackingSeed toSeedResponse(NarrativeCandidateTrackingSeedEntity e) {
        return new NarrativeShadowReviewResponse.TrackingSeed(e.getSymbol(), e.getStockName(), e.getRelatedTheme(),
                e.getLifecycleAtDetection(), safe(e.getAttentionScore()), safe(e.getCrowdingScore()), safe(e.getShadowDelta()),
                e.getDecisionDate(), true, false);
    }

    private String lifecycle(BigDecimal attention, BigDecimal crowding, Integer sourceCount) {
        if (crowding.compareTo(new BigDecimal("8.0")) >= 0) return "CROWDED";
        if (attention.compareTo(new BigDecimal("8.0")) >= 0 && safeInt(sourceCount) >= 5) return "EXPANDING";
        if (attention.compareTo(new BigDecimal("6.5")) >= 0 && crowding.compareTo(new BigDecimal("6.5")) < 0) return "EMERGING";
        if (attention.compareTo(new BigDecimal("4.0")) >= 0) return "EARLY";
        return "NOISE";
    }

    private BigDecimal crowdingScore(String crowdingRisk) {
        String risk = crowdingRisk == null ? "LOW" : crowdingRisk.trim().toUpperCase();
        return switch (risk) {
            case "HIGH" -> new BigDecimal("8.700");
            case "MEDIUM" -> new BigDecimal("5.200");
            default -> new BigDecimal("3.100");
        };
    }

    private BigDecimal sourceDensity(Integer sourceCount) {
        return new BigDecimal(Math.min(10, safeInt(sourceCount) * 2)).setScale(3, RoundingMode.HALF_UP);
    }

    private BigDecimal tenPoint(BigDecimal score0to1) {
        return safe(score0to1).multiply(BigDecimal.TEN).setScale(3, RoundingMode.HALF_UP);
    }

    private BigDecimal safe(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private int safeInt(Integer value) { return value == null ? 0 : value; }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<List<String>>() {}); }
        catch (Exception e) { return List.of(); }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { return "{}"; }
    }

    private <T> T read(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) return fallback;
        try { return objectMapper.readValue(json, type); }
        catch (Exception e) { return fallback; }
    }
}
