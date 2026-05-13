package com.austin.trading.engine;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class KolThemeSignalEngine {

    private static final BigDecimal POSITIVE_CAP = new BigDecimal("0.2000");
    private static final BigDecimal NEGATIVE_CAP = new BigDecimal("-0.3000");

    public BigDecimal evidenceWeight(String evidenceType) {
        return switch (normalize(evidenceType)) {
            case "DIRECT_CLAIM" -> new BigDecimal("1.0");
            case "REASONED_ANALYSIS" -> new BigDecimal("0.7");
            case "SECOND_HAND" -> new BigDecimal("0.4");
            case "UNKNOWN" -> new BigDecimal("0.2");
            case "RUMOR" -> new BigDecimal("0.1");
            case "PAID_PROMOTION", "SUSPECTED_PUMP" -> BigDecimal.ZERO;
            default -> new BigDecimal("0.2");
        };
    }

    public SnapshotCalculation calculate(String themeTag, String direction, List<EvidenceInput> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return new SnapshotCalculation(themeTag, normalizeDirection(direction), 0, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "LOW", List.of(), Map.of());
        }

        Map<String, List<EvidenceInput>> bySource = evidence.stream()
                .collect(Collectors.groupingBy(e -> blankToUnknown(e.sourceKey())));
        List<SourceContribution> contributions = new ArrayList<>();
        for (Map.Entry<String, List<EvidenceInput>> entry : bySource.entrySet()) {
            BigDecimal sourceScore = BigDecimal.ZERO;
            List<BigDecimal> weighted = entry.getValue().stream()
                    .map(this::weightedEvidence)
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (int i = 0; i < weighted.size(); i++) {
                BigDecimal discount = i == 0 ? BigDecimal.ONE : new BigDecimal("0.35");
                sourceScore = sourceScore.add(weighted.get(i).multiply(discount));
            }
            sourceScore = sourceScore.min(BigDecimal.ONE);
            contributions.add(new SourceContribution(entry.getKey(), sourceScore));
        }

        BigDecimal total = contributions.stream()
                .map(SourceContribution::score)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int sourceCount = contributions.size();
        int evidenceCount = evidence.size();
        String crowdingRisk = crowdingRisk(sourceCount, evidenceCount);
        BigDecimal normalized = total.divide(BigDecimal.valueOf(Math.max(1, sourceCount)), 6, RoundingMode.HALF_UP);
        BigDecimal crowdingMultiplier = switch (crowdingRisk) {
            case "HIGH" -> new BigDecimal("0.45");
            case "MEDIUM" -> new BigDecimal("0.75");
            default -> BigDecimal.ONE;
        };

        String normalizedDirection = normalizeDirection(direction);
        BigDecimal positiveScore = "POSITIVE".equals(normalizedDirection) ? normalized : BigDecimal.ZERO;
        BigDecimal negativeScore = "NEGATIVE".equals(normalizedDirection) ? normalized : BigDecimal.ZERO;
        BigDecimal boost = BigDecimal.ZERO;
        if ("POSITIVE".equals(normalizedDirection)) {
            boost = normalized.multiply(POSITIVE_CAP).multiply(crowdingMultiplier).min(POSITIVE_CAP);
        } else if ("NEGATIVE".equals(normalizedDirection)) {
            boost = normalized.multiply(NEGATIVE_CAP).max(NEGATIVE_CAP);
        }

        List<String> topSources = contributions.stream()
                .sorted(Comparator.comparing(SourceContribution::score).reversed())
                .limit(5)
                .map(SourceContribution::sourceKey)
                .toList();
        Map<String, Object> trace = Map.of(
                "weakSignalOnly", true,
                "positiveBoostCap", POSITIVE_CAP,
                "negativeBoostCap", NEGATIVE_CAP,
                "sourceDiscount", "same source additional evidence x0.35, source contribution capped at 1.0"
        );
        return new SnapshotCalculation(themeTag, normalizedDirection, sourceCount, evidenceCount,
                scaled(positiveScore), scaled(negativeScore), scaled(boost), crowdingRisk, topSources, trace);
    }

    private BigDecimal weightedEvidence(EvidenceInput input) {
        BigDecimal confidence = input.confidence() != null ? input.confidence() : BigDecimal.ONE;
        confidence = confidence.max(BigDecimal.ZERO).min(BigDecimal.ONE);
        BigDecimal credibility = input.sourceCredibilityWeight() != null ? input.sourceCredibilityWeight() : BigDecimal.ONE;
        credibility = credibility.max(BigDecimal.ZERO).min(BigDecimal.ONE);
        return evidenceWeight(input.evidenceType()).multiply(confidence).multiply(credibility);
    }

    private static String crowdingRisk(int sourceCount, int evidenceCount) {
        if (sourceCount >= 5 || evidenceCount >= 10) return "HIGH";
        if (sourceCount >= 3 || evidenceCount >= 5) return "MEDIUM";
        return "LOW";
    }

    public static String normalizeDirection(String direction) {
        String value = normalize(direction);
        if (value.equals("BULLISH")) return "POSITIVE";
        if (value.equals("BEARISH")) return "NEGATIVE";
        if (value.equals("POSITIVE") || value.equals("NEGATIVE") || value.equals("NEUTRAL")) return value;
        return "UNKNOWN";
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim();
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    public record EvidenceInput(
            String sourceKey,
            String evidenceType,
            String direction,
            BigDecimal confidence,
            BigDecimal sourceCredibilityWeight
    ) {
    }

    public record SourceContribution(String sourceKey, BigDecimal score) {
    }

    public record SnapshotCalculation(
            String themeTag,
            String direction,
            int sourceCount,
            int evidenceCount,
            BigDecimal positiveScore,
            BigDecimal negativeScore,
            BigDecimal netShadowBoost,
            String crowdingRisk,
            List<String> topSources,
            Map<String, Object> trace
    ) {
    }
}
