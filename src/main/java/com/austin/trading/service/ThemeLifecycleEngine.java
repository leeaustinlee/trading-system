package com.austin.trading.service;

import com.austin.trading.dto.response.ThemeLifecycleResponse;
import com.austin.trading.dto.response.ThemeReplayMetricsResponse;
import com.austin.trading.entity.ThemeLifecycleStateEntity;
import com.austin.trading.entity.ThemeReplayNodeEntity;
import com.austin.trading.entity.ThemeReplaySnapshotEntity;
import com.austin.trading.repository.ThemeLifecycleStateRepository;
import com.austin.trading.repository.ThemeReplayNodeRepository;
import com.austin.trading.repository.ThemeReplaySnapshotRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ThemeLifecycleEngine {

    public static final String EMERGING = "EMERGING";
    public static final String MAINSTREAM = "MAINSTREAM";
    public static final String OVERHEATED = "OVERHEATED";
    public static final String DISTRIBUTION = "DISTRIBUTION";
    public static final String DEAD = "DEAD";

    private static final ThemeLifecycleResponse.SafetyBoundary SAFETY = ThemeLifecycleResponse.SafetyBoundary.lifecycleReplayOnlyBoundary();

    private final ThemeLifecycleStateRepository lifecycleRepository;
    private final ThemeReplaySnapshotRepository snapshotRepository;
    private final ThemeReplayNodeRepository nodeRepository;
    private final ReplayMetricsService replayMetricsService;
    private final ObjectMapper objectMapper;

    public ThemeLifecycleEngine(
            ThemeLifecycleStateRepository lifecycleRepository,
            ThemeReplaySnapshotRepository snapshotRepository,
            ThemeReplayNodeRepository nodeRepository,
            ObjectMapper objectMapper
    ) {
        this(lifecycleRepository, snapshotRepository, nodeRepository, null, objectMapper);
    }

    @Autowired
    public ThemeLifecycleEngine(
            ThemeLifecycleStateRepository lifecycleRepository,
            ThemeReplaySnapshotRepository snapshotRepository,
            ThemeReplayNodeRepository nodeRepository,
            ReplayMetricsService replayMetricsService,
            ObjectMapper objectMapper
    ) {
        this.lifecycleRepository = lifecycleRepository;
        this.snapshotRepository = snapshotRepository;
        this.nodeRepository = nodeRepository;
        this.replayMetricsService = replayMetricsService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ThemeLifecycleResponse get(LocalDate date) {
        return response(date, lifecycleRepository.findByTradingDateOrderByThemeTagAsc(date));
    }

    @Transactional(readOnly = true)
    public ThemeLifecycleResponse getTheme(LocalDate date, String themeTag) {
        return response(date, lifecycleRepository.findByTradingDateAndThemeTag(date, themeTag).stream().toList());
    }

    @Transactional
    public ThemeLifecycleResponse.BuildResult build(LocalDate date) {
        List<ThemeReplaySnapshotEntity> snapshots = snapshotRepository.findByTradingDateOrderByThemeTagAsc(date);
        List<ThemeLifecycleStateEntity> states = new ArrayList<>();
        for (ThemeReplaySnapshotEntity snapshot : snapshots) {
            List<ThemeReplayNodeEntity> nodes = nodeRepository.findByTradingDateAndThemeTagOrderByIdAsc(date, snapshot.getThemeTag());
            states.add(evaluate(date, snapshot, nodes));
        }
        lifecycleRepository.deleteByTradingDate(date);
        lifecycleRepository.flush();
        lifecycleRepository.saveAll(states);
        Map<String, ThemeLifecycleStateEntity> byTheme = states.stream().collect(Collectors.toMap(
                ThemeLifecycleStateEntity::getThemeTag,
                s -> s,
                (a, b) -> a));
        snapshots.forEach(snapshot -> {
            ThemeLifecycleStateEntity state = byTheme.get(snapshot.getThemeTag());
            if (state != null) {
                snapshot.setLifecycleStage(state.getStage());
                snapshot.setLifecycleScore(state.getLifecycleScore());
                snapshot.setLifecycleReason(state.getReason());
                snapshot.setRecommendedPlaybookJson(state.getRecommendedPlaybookJson());
                snapshot.setAvoidPlaybookJson(state.getAvoidPlaybookJson());
            }
        });
        snapshotRepository.saveAll(snapshots);
        Map<String, String> stages = states.stream().collect(Collectors.toMap(
                ThemeLifecycleStateEntity::getThemeTag,
                ThemeLifecycleStateEntity::getStage,
                (a, b) -> a,
                LinkedHashMap::new));
        return new ThemeLifecycleResponse.BuildResult(
                date,
                states.size(),
                true,
                true,
                SAFETY,
                stages,
                states.stream().map(this::toItem).toList()
        );
    }

    public ThemeLifecycleStateEntity evaluate(LocalDate date, ThemeReplaySnapshotEntity snapshot, List<ThemeReplayNodeEntity> nodes) {
        Metrics metrics = metrics(snapshot, nodes);
        StageDecision decision = decide(metrics);
        ThemeLifecycleStateEntity state = new ThemeLifecycleStateEntity();
        state.setTradingDate(date);
        state.setThemeTag(snapshot.getThemeTag());
        state.setStage(decision.stage());
        Optional<ThemeLifecycleStateEntity> previous = lifecycleRepository.findFirstByThemeTagAndTradingDateLessThanOrderByTradingDateDesc(snapshot.getThemeTag(), date);
        state.setPreviousStage(previous.map(ThemeLifecycleStateEntity::getStage).orElse(null));
        state.setStageChanged(previous.map(p -> !decision.stage().equals(p.getStage())).orElse(false));
        state.setStageConfidence(decision.confidence());
        state.setLeaderCount(metrics.leaderCount());
        state.setBreadth(metrics.breadth());
        state.setVolumeExpansion(metrics.volumeExpansion());
        state.setContinuationDays(metrics.continuationDays());
        state.setRotationScore(metrics.rotationScore());
        state.setCrowdingScore(metrics.crowdingScore());
        state.setLimitUpDensity(metrics.limitUpDensity());
        state.setNarrativeDensity(metrics.narrativeDensity());
        state.setInstitutionalFlowScore(metrics.institutionalFlowScore());
        state.setLifecycleScore(decision.lifecycleScore());
        state.setReason(decision.reason());
        state.setRecommendedPlaybookJson(json(decision.recommendedPlaybook()));
        state.setAvoidPlaybookJson(json(decision.avoidPlaybook()));
        state.setScoreJson(json(Map.of(
                "leaderCount", metrics.leaderCount(),
                "breadth", metrics.breadth(),
                "volumeExpansion", metrics.volumeExpansion(),
                "continuationDays", metrics.continuationDays(),
                "rotationScore", metrics.rotationScore(),
                "crowdingScore", metrics.crowdingScore(),
                "limitUpDensity", metrics.limitUpDensity(),
                "narrativeDensity", metrics.narrativeDensity(),
                "institutionalFlowScore", metrics.institutionalFlowScore()
        )));
        state.setPayloadJson(json(Map.of(
                "replayOnly", true,
                "advisoryOnly", true,
                "doesNotAffectFinalDecision", true,
                "doesNotAffectBuySellEnter", true,
                "lifecycleDoesNotOverrideRiskGate", true,
                "recommendedPlaybook", decision.recommendedPlaybook(),
                "avoidPlaybook", decision.avoidPlaybook()
        )));
        return state;
    }

    public Metrics metrics(ThemeReplaySnapshotEntity snapshot, List<ThemeReplayNodeEntity> nodes) {
        int leaderCount = intValue(snapshot.getLeaderCount());
        int breadth = Math.max(intValue(snapshot.getBreadth()), nodes == null ? 0 : nodes.size());
        int peerCount = intValue(snapshot.getPeerCount());
        int divergenceCount = intValue(snapshot.getDivergenceCount());
        BigDecimal maxShadow = safeNodes(nodes).stream()
                .map(ThemeReplayNodeEntity::getShadowRankScore)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        BigDecimal avgThemeImportance = average(safeNodes(nodes).stream()
                .map(ThemeReplayNodeEntity::getThemeImportanceScore)
                .filter(Objects::nonNull)
                .toList());
        BigDecimal volumeExpansion = readDecimal(snapshot.getPayloadJson(), "volumeExpansion", avgThemeImportance.divide(new BigDecimal("10"), 4, RoundingMode.HALF_UP));
        int continuationDays = readInt(snapshot.getPayloadJson(), "continuationDays", Math.max(0, Math.min(5, breadth / 2)));
        BigDecimal rotationScore = readDecimal(snapshot.getPayloadJson(), "rotationScore", breadth == 0 ? BigDecimal.ZERO : new BigDecimal(peerCount).divide(new BigDecimal(Math.max(1, breadth)), 4, RoundingMode.HALF_UP));
        BigDecimal crowdingScore = readDecimal(snapshot.getPayloadJson(), "crowdingScore", maxShadow.divide(new BigDecimal("10"), 4, RoundingMode.HALF_UP));
        BigDecimal limitUpDensity = readDecimal(snapshot.getPayloadJson(), "limitUpDensity", BigDecimal.ZERO);
        BigDecimal narrativeDensity = readDecimal(snapshot.getPayloadJson(), "narrativeDensity", avgThemeImportance.divide(new BigDecimal("10"), 4, RoundingMode.HALF_UP));
        BigDecimal institutionalFlow = readDecimal(snapshot.getPayloadJson(), "institutionalFlowScore", avgThemeImportance.divide(new BigDecimal("12"), 4, RoundingMode.HALF_UP));
        if (divergenceCount > 0) {
            crowdingScore = crowdingScore.max(new BigDecimal("0.6500"));
        }
        return new Metrics(leaderCount, breadth, volumeExpansion, continuationDays, rotationScore, crowdingScore,
                limitUpDensity, narrativeDensity, institutionalFlow, divergenceCount);
    }

    public StageDecision decide(Metrics m) {
        BigDecimal lifecycleScore = score(m);
        if (m.breadth() <= 1 && m.leaderCount() == 0 && m.continuationDays() == 0 && lt(m.narrativeDensity(), "0.2500")) {
            return decision(DEAD, lifecycleScore, "breadth/narrative/leader all faded; continuation broken", List.of("REMOVE_FROM_ACTIVE_THEME_FOCUS"), List.of("CHASE_LEADER"));
        }
        if ((m.divergenceCount() >= 2 || gte(m.crowdingScore(), "0.6500")) && m.leaderCount() > 0 && m.continuationDays() <= 1) {
            return decision(DISTRIBUTION, lifecycleScore, "leader weakening or divergence increasing while continuation is weak", List.of("RISK_WARNING", "REPLAY_ANNOTATION"), List.of("CHASE_LEADER", "ADD_ON_WEAK_CONTINUATION"));
        }
        if (gte(m.limitUpDensity(), "0.3500") || gte(m.crowdingScore(), "0.8000") || gte(m.narrativeDensity(), "0.8500")) {
            return decision(OVERHEATED, lifecycleScore, "limit-up/crowding/narrative density is overheated; advisory only, avoid chasing", List.of("ADVISORY_ONLY", "AVOID_CHASING"), List.of("CHASE_LEADER", "LIFECYCLE_SCORE_BOOST"));
        }
        if (m.breadth() >= 5 && m.continuationDays() >= 2 && m.leaderCount() >= 1 && gte(m.rotationScore(), "0.3000")) {
            return decision(MAINSTREAM, lifecycleScore, "leader is clear, breadth expanded, continuation and peer rotation are positive", List.of("LOW_BASE_FOLLOWER", "PULLBACK"), List.of("CHASE_LEADER"));
        }
        return decision(EMERGING, lifecycleScore, "narrative/volume is forming but breadth and continuation are still early", List.of("RESEARCH_UNIVERSE_EXPANSION", "LOW_BASE_OBSERVATION"), List.of("LIFECYCLE_DIRECT_BUY"));
    }

    public ThemeLifecycleResponse.SafetyBoundary safetyBoundary() {
        return SAFETY;
    }

    public ThemeLifecycleResponse.Item toItem(ThemeLifecycleStateEntity e) {
        return new ThemeLifecycleResponse.Item(
                e.getTradingDate(), e.getThemeTag(), e.getStage(), e.getPreviousStage(), Boolean.TRUE.equals(e.getStageChanged()),
                e.getStageConfidence(), intValue(e.getLeaderCount()), intValue(e.getBreadth()), e.getVolumeExpansion(),
                intValue(e.getContinuationDays()), e.getRotationScore(), e.getCrowdingScore(), e.getLimitUpDensity(),
                e.getNarrativeDensity(), e.getInstitutionalFlowScore(), e.getLifecycleScore(), e.getReason(),
                stringList(e.getRecommendedPlaybookJson()), stringList(e.getAvoidPlaybookJson()), e.getPayloadJson(), SAFETY);
    }


    private ThemeReplayMetricsResponse.MetricsSummary metricsSummary(LocalDate date, List<ThemeLifecycleStateEntity> states) {
        if (replayMetricsService == null || states == null || states.isEmpty()) {
            return ThemeReplayMetricsResponse.MetricsSummary.empty();
        }
        String theme = states.get(0).getThemeTag();
        boolean singleTheme = states.stream().allMatch(s -> Objects.equals(theme, s.getThemeTag()));
        return singleTheme ? replayMetricsService.summary(date, theme) : ThemeReplayMetricsResponse.MetricsSummary.empty();
    }

    private ThemeLifecycleResponse response(LocalDate date, List<ThemeLifecycleStateEntity> states) {
        return new ThemeLifecycleResponse(date, true, true, SAFETY, metricsSummary(date, states), states.stream().map(this::toItem).toList());
    }

    private StageDecision decision(String stage, BigDecimal score, String reason, List<String> recommended, List<String> avoid) {
        BigDecimal confidence = score.abs().min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
        return new StageDecision(stage, confidence, score, reason, recommended, avoid);
    }

    private BigDecimal score(Metrics m) {
        BigDecimal score = BigDecimal.ZERO;
        score = score.add(new BigDecimal(Math.min(m.breadth(), 10)).multiply(new BigDecimal("0.0500")));
        score = score.add(new BigDecimal(Math.min(m.leaderCount(), 3)).multiply(new BigDecimal("0.1000")));
        score = score.add(m.volumeExpansion().multiply(new BigDecimal("0.2000")));
        score = score.add(new BigDecimal(Math.min(m.continuationDays(), 5)).multiply(new BigDecimal("0.0500")));
        score = score.add(m.rotationScore().multiply(new BigDecimal("0.2000")));
        score = score.add(m.institutionalFlowScore().multiply(new BigDecimal("0.1000")));
        score = score.subtract(m.crowdingScore().multiply(new BigDecimal("0.1200")));
        score = score.subtract(m.limitUpDensity().multiply(new BigDecimal("0.1800")));
        score = score.subtract(new BigDecimal(m.divergenceCount()).multiply(new BigDecimal("0.0500")));
        return score.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal average(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(new BigDecimal(values.size()), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal readDecimal(String json, String key, BigDecimal fallback) {
        JsonNode node = readNode(json, key);
        if (node == null || node.isNull()) return fallback == null ? BigDecimal.ZERO : fallback;
        if (node.isNumber()) return node.decimalValue().setScale(4, RoundingMode.HALF_UP);
        if (node.isTextual()) {
            try { return new BigDecimal(node.asText()).setScale(4, RoundingMode.HALF_UP); } catch (NumberFormatException ignored) { return fallback; }
        }
        return fallback == null ? BigDecimal.ZERO : fallback;
    }

    private int readInt(String json, String key, int fallback) {
        JsonNode node = readNode(json, key);
        if (node == null || node.isNull()) return fallback;
        if (node.isNumber()) return node.asInt();
        if (node.isTextual()) {
            try { return Integer.parseInt(node.asText()); } catch (NumberFormatException ignored) { return fallback; }
        }
        return fallback;
    }

    private JsonNode readNode(String json, String key) {
        if (json == null || json.isBlank()) return null;
        try { return objectMapper.readTree(json).get(key); } catch (Exception ignored) { return null; }
    }

    private List<String> stringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) return List.of();
            List<String> out = new ArrayList<>();
            node.forEach(n -> out.add(n.asText()));
            return out;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { return "{}"; }
    }

    private boolean gte(BigDecimal value, String threshold) { return value != null && value.compareTo(new BigDecimal(threshold)) >= 0; }
    private boolean lt(BigDecimal value, String threshold) { return value == null || value.compareTo(new BigDecimal(threshold)) < 0; }
    private int intValue(Integer value) { return value == null ? 0 : value; }
    private List<ThemeReplayNodeEntity> safeNodes(List<ThemeReplayNodeEntity> nodes) { return nodes == null ? List.of() : nodes; }

    public record Metrics(
            int leaderCount,
            int breadth,
            BigDecimal volumeExpansion,
            int continuationDays,
            BigDecimal rotationScore,
            BigDecimal crowdingScore,
            BigDecimal limitUpDensity,
            BigDecimal narrativeDensity,
            BigDecimal institutionalFlowScore,
            int divergenceCount
    ) {}

    public record StageDecision(
            String stage,
            BigDecimal confidence,
            BigDecimal lifecycleScore,
            String reason,
            List<String> recommendedPlaybook,
            List<String> avoidPlaybook
    ) {}
}
