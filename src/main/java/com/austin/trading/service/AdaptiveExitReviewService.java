package com.austin.trading.service;

import com.austin.trading.dto.response.AdaptiveExitReviewResponse;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.entity.PositionThesisLedgerEntity;
import com.austin.trading.entity.StopOutcomeLedgerEntity;
import com.austin.trading.repository.PositionRepository;
import com.austin.trading.repository.StopOutcomeLedgerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AdaptiveExitReviewService {
    private static final int EVIDENCE_LOOKBACK_DAYS = 90;

    private final PositionRepository positionRepository;
    private final PortfolioHealthV2Service healthV2Service;
    private final StopOutcomeLedgerRepository stopOutcomeLedgerRepository;
    private final PositionThesisLedgerService thesisLedgerService;

    public AdaptiveExitReviewService(PositionRepository positionRepository,
                                     PortfolioHealthV2Service healthV2Service,
                                     StopOutcomeLedgerRepository stopOutcomeLedgerRepository,
                                     PositionThesisLedgerService thesisLedgerService) {
        this.positionRepository = positionRepository;
        this.healthV2Service = healthV2Service;
        this.stopOutcomeLedgerRepository = stopOutcomeLedgerRepository;
        this.thesisLedgerService = thesisLedgerService;
    }

    @Transactional(readOnly = true)
    public AdaptiveExitReviewResponse reviewOpenPositions() {
        List<PositionEntity> positions = positionRepository.findByStatus("OPEN");
        Map<String, Map<String, Object>> healthBySymbol = healthRowsBySymbol();
        List<AdaptiveExitReviewResponse.Item> items = positions.stream()
                .map(p -> reviewPosition(p, healthBySymbol.get(p.getSymbol())))
                .toList();
        return AdaptiveExitReviewResponse.of(items);
    }

    @Transactional(readOnly = true)
    public AdaptiveExitReviewResponse reviewSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return AdaptiveExitReviewResponse.of(List.of());
        Optional<PositionEntity> position = positionRepository.findTopBySymbolAndStatus(symbol, "OPEN");
        if (position.isEmpty()) return AdaptiveExitReviewResponse.of(List.of());
        Map<String, Map<String, Object>> healthBySymbol = healthRowsBySymbol();
        return AdaptiveExitReviewResponse.of(List.of(reviewPosition(position.get(), healthBySymbol.get(symbol))));
    }

    private AdaptiveExitReviewResponse.Item reviewPosition(PositionEntity p, Map<String, Object> health) {
        BigDecimal currentPrice = bd(healthValue(health, "currentPrice"));
        BigDecimal avgCost = p.getAvgCost();
        BigDecimal pnlPct = pct(currentPrice, avgCost);
        boolean stopHit = stopHit(currentPrice, p.getStopLossPrice(), p.getTrailingStopPrice());
        Map<String, Object> originalExitSignal = originalExitSignal(p, currentPrice, stopHit);
        Map<String, Object> healthSignal = healthSignal(health);
        List<StopOutcomeLedgerEntity> ledgers = loadEvidence(p.getSymbol());
        Map<String, Object> evidence = stopOutcomeEvidence(ledgers);
        boolean themeActive = themeStillActive(health);
        String structureStatus = str(healthValue(health, "structureStatus"));
        Optional<PositionThesisLedgerEntity> thesis = loadThesis(p.getSymbol());
        String thesisStatus = thesis.map(PositionThesisLedgerEntity::getThesisStatus).orElse("UNKNOWN");
        String thesisSummary = thesis.map(PositionThesisLedgerEntity::getThesisSummary).orElse(null);
        String invalidationCondition = thesis.map(PositionThesisLedgerEntity::getInvalidationCondition).orElse(null);
        String themeLifecycle = thesis.map(PositionThesisLedgerEntity::getThemeLifecycle).orElse("UNKNOWN");
        BigDecimal narrativeHeat = thesis.map(PositionThesisLedgerEntity::getNarrativeHeat).orElse(null);
        String crowdingRisk = thesis.map(PositionThesisLedgerEntity::getCrowdingRisk).orElse("UNKNOWN");
        String wavePhase = thesis.map(PositionThesisLedgerEntity::getWavePhase).orElse("UNKNOWN");
        BigDecimal rotationStrength = thesis.map(PositionThesisLedgerEntity::getRotationStrength).orElse(null);
        String institutionalAlignment = thesis.map(PositionThesisLedgerEntity::getInstitutionalAlignment).orElse("UNKNOWN");
        String sectorLeadership = thesis.map(PositionThesisLedgerEntity::getSectorLeadership).orElse("UNKNOWN");
        boolean narrativeThemeActive = thesis.map(PositionThesisLedgerEntity::getThemeStillActive).orElse(themeActive);
        boolean effectiveThemeActive = themeActive || narrativeThemeActive;
        AdaptiveExitReviewResponse.Recommendation recommendation = recommend(
                stopHit, pnlPct, health, evidence, effectiveThemeActive, currentPrice, thesisStatus,
                themeLifecycle, narrativeHeat, crowdingRisk, wavePhase, rotationStrength,
                institutionalAlignment, sectorLeadership);
        return AdaptiveExitReviewResponse.Item.create(
                p.getSymbol(), currentPrice, avgCost, pnlPct,
                originalExitSignal, healthSignal, evidence, effectiveThemeActive, structureStatus,
                thesisStatus, thesisSummary, invalidationCondition,
                themeLifecycle, narrativeHeat, crowdingRisk, wavePhase, rotationStrength,
                institutionalAlignment, sectorLeadership, recommendation);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> healthRowsBySymbol() {
        if (healthV2Service == null) return Map.of();
        try {
            Map<String, Object> summary = healthV2Service.healthV2ReadOnlySummary();
            Object positions = summary == null ? null : summary.get("positions");
            if (!(positions instanceof List<?> rows)) return Map.of();
            return rows.stream()
                    .filter(Map.class::isInstance)
                    .map(row -> (Map<String, Object>) row)
                    .filter(row -> row.get("symbol") != null)
                    .collect(Collectors.toMap(row -> String.valueOf(row.get("symbol")), Function.identity(), (a, b) -> a));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private AdaptiveExitReviewResponse.Recommendation recommend(boolean stopHit,
                                                                BigDecimal pnlPct,
                                                                Map<String, Object> health,
                                                                Map<String, Object> evidence,
                                                                boolean themeActive,
                                                                BigDecimal currentPrice,
                                                                String thesisStatus,
                                                                String themeLifecycle,
                                                                BigDecimal narrativeHeat,
                                                                String crowdingRisk,
                                                                String wavePhase,
                                                                BigDecimal rotationStrength,
                                                                String institutionalAlignment,
                                                                String sectorLeadership) {
        boolean structureIntact = structureIntact(health);
        boolean structureBroken = structureBroken(health);
        boolean volumeBreakdown = contains(healthValue(health, "volumeStatus"), "VOLUME_BREAKDOWN")
                || signalsContain(health, "volume_breakdown")
                || contains(healthValue(health, "volumeStatus"), "LONG_BLACK");
        boolean relativeWeak = contains(healthValue(health, "relativeStrengthStatus"), "UNDERPERFORM")
                || signalsContain(health, "relative_strength_weak");
        boolean healthHardExit = contains(healthValue(health, "actionTier"), "HARD_EXIT")
                || contains(healthValue(health, "structuralTier"), "HARD_EXIT");
        boolean washoutCommon = ((Number) evidence.getOrDefault("washoutReversalCount", 0)).intValue() > 0
                && ((Number) evidence.getOrDefault("washoutReversalCount", 0)).intValue()
                >= ((Number) evidence.getOrDefault("trueBreakdownCount", 0)).intValue();
        boolean trueBreakdownCommon = ((Number) evidence.getOrDefault("trueBreakdownCount", 0)).intValue() > 0
                && ((Number) evidence.getOrDefault("trueBreakdownCount", 0)).intValue()
                >= ((Number) evidence.getOrDefault("washoutReversalCount", 0)).intValue();
        boolean weakening = contains(healthValue(health, "actionTier"), "REDUCE")
                || contains(healthValue(health, "structureStatus"), "MA5_BREAK")
                || contains(healthValue(health, "volumeStatus"), "WEAK")
                || relativeWeak;
        boolean lifecycleActive = contains(themeLifecycle, "ACTIVE") || contains(themeLifecycle, "MAINSTREAM");
        boolean lifecycleDead = contains(themeLifecycle, "FADING") || contains(themeLifecycle, "DEAD");
        boolean narrativeHigh = narrativeHeat != null && narrativeHeat.compareTo(new BigDecimal("7.0")) >= 0;
        boolean narrativeOverheated = narrativeHeat != null && narrativeHeat.compareTo(new BigDecimal("9.0")) >= 0;
        boolean crowdingHigh = contains(crowdingRisk, "HIGH");
        boolean midTrend = contains(wavePhase, "MID_TREND_CONTINUATION");
        boolean lateExtension = contains(wavePhase, "LATE_EXTENSION");
        boolean institutionalStrong = contains(institutionalAlignment, "STRONG") || contains(institutionalAlignment, "POSITIVE");
        boolean institutionalNegative = contains(institutionalAlignment, "NEGATIVE");
        boolean rotationPositive = rotationStrength == null || rotationStrength.compareTo(BigDecimal.ZERO) > 0;
        boolean leadershipStrong = contains(sectorLeadership, "STRONG");

        if (stopHit && (contains(thesisStatus, "INVALIDATED") || (lifecycleDead && structureBroken && relativeWeak && institutionalNegative && trueBreakdownCommon))) {
            return AdaptiveExitReviewResponse.Recommendation.HARD_EXIT;
        }
        if (stopHit && structureIntact && (lifecycleActive || themeActive) && narrativeHigh && institutionalStrong && rotationPositive && washoutCommon) {
            return AdaptiveExitReviewResponse.Recommendation.OBSERVE_1D;
        }
        if (!stopHit && midTrend && leadershipStrong && contains(thesisStatus, "ACTIVE") && !relativeWeak) {
            return AdaptiveExitReviewResponse.Recommendation.HOLD;
        }
        if (!stopHit && contains(thesisStatus, "ACTIVE") && (crowdingHigh || lateExtension || narrativeOverheated) && (relativeWeak || !leadershipStrong || lateExtension)) {
            return AdaptiveExitReviewResponse.Recommendation.REDUCE_REVIEW;
        }
        if (stopHit && contains(thesisStatus, "ACTIVE") && structureIntact) {
            return AdaptiveExitReviewResponse.Recommendation.OBSERVE_1D;
        }
        if (stopHit && (healthHardExit || (structureBroken && (volumeBreakdown || relativeWeak || !themeActive || trueBreakdownCommon)))) {
            return AdaptiveExitReviewResponse.Recommendation.HARD_EXIT;
        }
        if (stopHit && structureIntact && themeActive && washoutCommon) {
            return AdaptiveExitReviewResponse.Recommendation.OBSERVE_1D;
        }
        if (stopHit) {
            return AdaptiveExitReviewResponse.Recommendation.EXIT_REVIEW;
        }
        if (isPositive(pnlPct) && weakening) {
            return AdaptiveExitReviewResponse.Recommendation.REDUCE_REVIEW;
        }
        if (healthyPosition(health, currentPrice)) {
            return AdaptiveExitReviewResponse.Recommendation.HOLD;
        }
        if (weakening) {
            return AdaptiveExitReviewResponse.Recommendation.REDUCE_REVIEW;
        }
        return AdaptiveExitReviewResponse.Recommendation.EXIT_REVIEW;
    }

    private boolean healthyPosition(Map<String, Object> health, BigDecimal currentPrice) {
        if (health == null || health.isEmpty()) return false;
        boolean actionHold = contains(healthValue(health, "actionTier"), "HOLD");
        boolean rsOk = !contains(healthValue(health, "relativeStrengthStatus"), "UNDERPERFORM");
        BigDecimal ma10 = technical(health, "ma10");
        BigDecimal ma20 = technical(health, "ma20");
        boolean aboveMa10 = currentPrice != null && ma10 != null && currentPrice.compareTo(ma10) >= 0;
        boolean aboveMa20 = currentPrice != null && ma20 != null && currentPrice.compareTo(ma20) >= 0;
        return actionHold && rsOk && structureIntact(health) && (aboveMa10 || aboveMa20);
    }

    @SuppressWarnings("unchecked")
    private BigDecimal technical(Map<String, Object> health, String key) {
        Object tech = healthValue(health, "technicals");
        if (tech instanceof Map<?, ?> m) return bd(((Map<String, Object>) m).get(key));
        return null;
    }

    private boolean stopHit(BigDecimal current, BigDecimal stopLoss, BigDecimal trailingStop) {
        if (current == null || current.signum() <= 0) return false;
        if (stopLoss != null && current.compareTo(stopLoss) <= 0) return true;
        return trailingStop != null && current.compareTo(trailingStop) <= 0;
    }

    private Map<String, Object> originalExitSignal(PositionEntity p, BigDecimal currentPrice, boolean stopHit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "PositionReview/PaperTrade stop plan snapshot");
        out.put("reviewStatus", p.getReviewStatus());
        out.put("currentPrice", currentPrice);
        out.put("stopLossPrice", p.getStopLossPrice());
        out.put("trailingStopPrice", p.getTrailingStopPrice());
        out.put("stopHit", stopHit);
        String label = stopHit ? "STOP_OR_TRAILING_HIT" : ("EXIT".equalsIgnoreCase(p.getReviewStatus()) ? "REVIEW_EXIT" : "NO_STOP_HIT");
        out.put("signal", label);
        return out;
    }

    private Map<String, Object> healthSignal(Map<String, Object> health) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("actionTier", healthValue(health, "actionTier"));
        out.put("structuralTier", healthValue(health, "structuralTier"));
        out.put("structureStatus", healthValue(health, "structureStatus"));
        out.put("volumeStatus", healthValue(health, "volumeStatus"));
        out.put("relativeStrengthStatus", healthValue(health, "relativeStrengthStatus"));
        out.put("chipStatus", healthValue(health, "chipStatus"));
        out.put("signals", healthValue(health, "structuralSignals"));
        out.put("reasons", healthValue(health, "reasons"));
        out.put("dataGaps", healthValue(health, "dataGaps"));
        return out;
    }

    private Optional<PositionThesisLedgerEntity> loadThesis(String symbol) {
        if (thesisLedgerService == null || symbol == null || symbol.isBlank()) return Optional.empty();
        try {
            Optional<PositionThesisLedgerEntity> row = thesisLedgerService.getOpenThesisBySymbol(symbol);
            return row == null ? Optional.empty() : row;
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private List<StopOutcomeLedgerEntity> loadEvidence(String symbol) {
        if (stopOutcomeLedgerRepository == null || symbol == null || symbol.isBlank()) return List.of();
        try {
            return stopOutcomeLedgerRepository.findBySymbolAndExitDateGreaterThanEqualOrderByExitDateDescIdDesc(
                    symbol, LocalDate.now().minusDays(EVIDENCE_LOOKBACK_DAYS));
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> stopOutcomeEvidence(List<StopOutcomeLedgerEntity> rows) {
        Map<String, Long> byLabel = new LinkedHashMap<>();
        Map<String, Long> byReason = new LinkedHashMap<>();
        List<Map<String, Object>> samples = new ArrayList<>();
        for (StopOutcomeLedgerEntity row : rows == null ? List.<StopOutcomeLedgerEntity>of() : rows) {
            byLabel.merge(str(row.getOutcomeLabel()), 1L, Long::sum);
            byReason.merge(str(row.getExitReason()), 1L, Long::sum);
            if (samples.size() < 5) {
                samples.add(Map.of(
                        "exitDate", value(row.getExitDate()),
                        "exitReason", value(row.getExitReason()),
                        "outcomeLabel", value(row.getOutcomeLabel()),
                        "maxReturnAfterExit", value(row.getMaxReturnAfterExit()),
                        "minReturnAfterExit", value(row.getMinReturnAfterExit())));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lookbackDays", EVIDENCE_LOOKBACK_DAYS);
        out.put("sampleCount", rows == null ? 0 : rows.size());
        out.put("byOutcomeLabel", byLabel);
        out.put("byExitReason", byReason);
        out.put("washoutReversalCount", byLabel.getOrDefault("WASHOUT_REVERSAL", 0L).intValue());
        out.put("trueBreakdownCount", byLabel.getOrDefault("TRUE_BREAKDOWN", 0L).intValue());
        out.put("mixedChopCount", byLabel.getOrDefault("MIXED_CHOP", 0L).intValue());
        out.put("samples", samples);
        out.put("evidenceScope", "READ_ONLY_STOP_OUTCOME_LEDGER");
        return out;
    }

    @SuppressWarnings("unchecked")
    private boolean themeStillActive(Map<String, Object> health) {
        Object inputs = healthValue(health, "healthInputs");
        if (inputs instanceof Map<?, ?> raw) {
            Map<String, Object> m = (Map<String, Object>) raw;
            Object mainstream = m.get("mainstreamTheme");
            Object stage = m.get("themeStage");
            return Boolean.TRUE.equals(mainstream) || contains(stage, "ACTIVE") || contains(stage, "MAINSTREAM");
        }
        return false;
    }

    private boolean structureIntact(Map<String, Object> health) {
        Object status = healthValue(health, "structureStatus");
        return signalsContain(health, "structure_intact")
                || contains(status, "INTACT")
                || (!contains(status, "BREAK") && !contains(status, "BROKEN") && !contains(status, "DATA_GAP"));
    }

    private boolean structureBroken(Map<String, Object> health) {
        Object status = healthValue(health, "structureStatus");
        return signalsContain(health, "structure_broken")
                || contains(status, "PREVIOUS_LOW_BREAK")
                || contains(status, "MA10_BREAK")
                || contains(status, "MA20_BREAK")
                || contains(status, "BROKEN");
    }

    @SuppressWarnings("unchecked")
    private boolean signalsContain(Map<String, Object> health, String needle) {
        Object signals = healthValue(health, "structuralSignals");
        if (signals instanceof List<?> list) {
            return list.stream().anyMatch(v -> contains(v, needle));
        }
        return contains(signals, needle);
    }

    private Object healthValue(Map<String, Object> health, String key) {
        return health == null ? null : health.get(key);
    }

    private BigDecimal pct(BigDecimal price, BigDecimal base) {
        if (price == null || base == null || base.signum() == 0) return null;
        return price.subtract(base).divide(base, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal bd(Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            if (v == null || "DATA_GAP".equalsIgnoreCase(String.valueOf(v))) return null;
            return new BigDecimal(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isPositive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    private boolean contains(Object value, String needle) {
        if (value == null || needle == null) return false;
        return String.valueOf(value).toUpperCase(Locale.ROOT).contains(needle.toUpperCase(Locale.ROOT));
    }

    private String str(Object value) {
        return value == null ? "DATA_GAP" : String.valueOf(value);
    }

    private Object value(Object v) {
        return v == null ? "DATA_GAP" : v;
    }
}
