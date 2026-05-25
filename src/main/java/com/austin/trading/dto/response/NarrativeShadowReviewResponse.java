package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record NarrativeShadowReviewResponse(
        LocalDate tradingDate,
        boolean weakSignalOnly,
        boolean shadowOnly,
        boolean observabilityOnly,
        boolean productionDecisionAllowed,
        String guardrail,
        List<ThemeSummary> themeSummary,
        Map<String, Long> lifecycleBoard,
        List<CandidateImpact> candidateImpact,
        List<String> warnings,
        RotationAnalysis rotationAnalysis,
        List<TrackingSeed> trackingSeeds,
        Map<String, Long> metrics,
        String note
) {
    public static NarrativeShadowReviewResponse empty(LocalDate date, String note) {
        return new NarrativeShadowReviewResponse(
                date,
                true,
                true,
                true,
                false,
                "Narrative/KOL is weak-signal context only; never a BUY/SELL/ENTER signal and never overrides hard gates.",
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                new RotationAnalysis(List.of(), List.of(), List.of(), List.of(), "no narrative rotation evidence"),
                List.of(),
                Map.of(
                        "narrative_theme_emerging_total", 0L,
                        "narrative_theme_crowded_total", 0L,
                        "narrative_shadow_boost_total", 0L,
                        "narrative_shadow_penalty_total", 0L,
                        "narrative_fomo_warning_total", 0L,
                        "narrative_candidate_tracking_total", 0L
                ),
                note
        );
    }

    public record ThemeSummary(
            String theme,
            String lifecycle,
            BigDecimal attentionScore,
            BigDecimal freshnessScore,
            BigDecimal crowdingScore,
            BigDecimal spreadVelocity,
            int sourceCount,
            List<String> topSources
    ) {}

    public record CandidateImpact(
            String symbol,
            String name,
            String relatedTheme,
            String lifecycle,
            BigDecimal baseScore,
            BigDecimal shadowDelta,
            boolean wouldUpgradeBucket,
            List<String> riskFlags,
            List<String> crowdingFlags
    ) {}

    public record RotationAnalysis(
            List<String> emergingThemes,
            List<String> expandingThemes,
            List<String> crowdedThemes,
            List<String> exhaustedThemes,
            String rotationSummary
    ) {}

    public record TrackingSeed(
            String symbol,
            String name,
            String relatedTheme,
            String lifecycleAtDetection,
            BigDecimal attentionScore,
            BigDecimal crowdingScore,
            BigDecimal shadowDelta,
            LocalDate decisionDate,
            boolean weakSignalOnly,
            boolean productionDecisionAllowed
    ) {}
}
