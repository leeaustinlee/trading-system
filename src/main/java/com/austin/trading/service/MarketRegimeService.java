package com.austin.trading.service;

import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.MarketRegimeInput;
import com.austin.trading.engine.MarketRegimeEngine;
import com.austin.trading.entity.MarketRegimeDecisionEntity;
import com.austin.trading.entity.MarketSnapshotEntity;
import com.austin.trading.repository.MarketRegimeDecisionRepository;
import com.austin.trading.repository.MarketSnapshotRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates {@link MarketRegimeEngine}:
 * <ol>
 *   <li>Build a {@link MarketRegimeInput} from the most recent
 *       {@code market_snapshot} (+ best-effort parse of its
 *       {@code payload_json}).</li>
 *   <li>Call the pure engine to classify the regime.</li>
 *   <li>Persist the decision to {@code market_regime_decision}.</li>
 * </ol>
 *
 * <p>Fields that the underlying snapshot cannot provide (breadth ratios,
 * leader ratio, MA distances, intraday volatility, etc.) are left as
 * {@code null} in the input — the engine substitutes conservative defaults
 * and the null-ness is preserved in {@code input_snapshot_json} so that
 * audit can tell real observations from fallbacks.</p>
 *
 * <p>Downstream layers (hourly-gate / monitor / final-decision) should read
 * {@link #getLatestForToday()} rather than re-classifying the market.</p>
 */
@Service
public class MarketRegimeService {

    private static final Logger log = LoggerFactory.getLogger(MarketRegimeService.class);

    private final MarketRegimeEngine            engine;
    private final MarketRegimeDecisionRepository regimeRepository;
    private final MarketSnapshotRepository      marketSnapshotRepository;
    private final ObjectMapper                  objectMapper;

    public MarketRegimeService(
            MarketRegimeEngine engine,
            MarketRegimeDecisionRepository regimeRepository,
            MarketSnapshotRepository marketSnapshotRepository,
            ObjectMapper objectMapper
    ) {
        this.engine                    = engine;
        this.regimeRepository          = regimeRepository;
        this.marketSnapshotRepository  = marketSnapshotRepository;
        this.objectMapper              = objectMapper;
    }

    // ── reads ──────────────────────────────────────────────────────────

    public Optional<MarketRegimeDecision> getLatestForToday() {
        return regimeRepository
                .findTopByTradingDateOrderByEvaluatedAtDescIdDesc(LocalDate.now())
                .map(MarketRegimeService::toDto);
    }

    public Optional<MarketRegimeDecision> getLatest() {
        return regimeRepository.findTopByOrderByEvaluatedAtDescIdDesc()
                .map(MarketRegimeService::toDto);
    }

    public List<MarketRegimeDecision> getHistory(int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        return regimeRepository.findRecent(null, null, PageRequest.of(0, safe))
                .stream().map(MarketRegimeService::toDto).toList();
    }

    // ── evaluate + persist ────────────────────────────────────────────

    /**
     * Evaluate using the latest {@code market_snapshot} as the data source.
     * No snapshot → returns empty (service won't fabricate a regime out of thin air).
     */
    @Transactional
    public Optional<MarketRegimeDecision> evaluateAndPersist() {
        Optional<MarketSnapshotEntity> snapshotOpt =
                marketSnapshotRepository.findTopByOrderByTradingDateDescCreatedAtDesc();
        if (snapshotOpt.isEmpty()) {
            log.warn("[MarketRegimeService] no market_snapshot available — skip regime eval");
            return Optional.empty();
        }
        MarketSnapshotEntity snap = snapshotOpt.get();
        MarketRegimeInput    input = buildInput(snap);
        MarketRegimeDecision decision = engine.evaluate(input);
        return Optional.of(persist(decision, snap.getId()));
    }

    /** Evaluate using a caller-provided input (for manual overrides / tests). */
    @Transactional
    public MarketRegimeDecision evaluateAndPersist(MarketRegimeInput input, Long marketSnapshotId) {
        MarketRegimeDecision decision = engine.evaluate(input);
        return persist(decision, marketSnapshotId);
    }

    // ── input builder ─────────────────────────────────────────────────

    /**
     * Pull what we can from {@code market_snapshot} + {@code payload_json}.
     * Any field we cannot derive is left null; the engine will substitute
     * conservative defaults and the fact is preserved in the snapshot JSON.
     */
    MarketRegimeInput buildInput(MarketSnapshotEntity snap) {
        JsonNode payload = parsePayload(snap.getPayloadJson());

        BigDecimal tsmcTrendScore         = readDecimal(payload, "tsmc_trend_score");
        BigDecimal breadthPositiveRatio   = readDecimal(payload, "breadth_positive_ratio");
        BigDecimal breadthNegativeRatio   = readDecimal(payload, "breadth_negative_ratio");
        BigDecimal leadersStrongRatio     = readDecimal(payload, "leaders_strong_ratio");
        BigDecimal indexDistanceFromMa10  = readDecimal(payload, "index_distance_from_ma10_pct");
        BigDecimal indexDistanceFromMa20  = readDecimal(payload, "index_distance_from_ma20_pct");
        BigDecimal intradayVolatilityPct  = readDecimal(payload, "intraday_volatility_pct");

        boolean washoutRebound   = readBool(payload, "washout_rebound",    false);
        boolean nearHighNotBreak = readBool(payload, "near_high_not_break",false);
        boolean blowoffSignal    = readBool(payload, "blowoff_signal",     false);

        return new MarketRegimeInput(
                snap.getTradingDate() != null ? snap.getTradingDate() : LocalDate.now(),
                LocalDateTime.now(),
                snap.getMarketGrade(),
                snap.getMarketPhase(),
                tsmcTrendScore,
                breadthPositiveRatio,
                breadthNegativeRatio,
                leadersStrongRatio,
                indexDistanceFromMa10,
                indexDistanceFromMa20,
                intradayVolatilityPct,
                washoutRebound,
                nearHighNotBreak,
                blowoffSignal
        );
    }

    // ── persistence ───────────────────────────────────────────────────

    private MarketRegimeDecision persist(MarketRegimeDecision decision, Long marketSnapshotId) {
        MarketRegimeDecisionEntity e = new MarketRegimeDecisionEntity();
        e.setTradingDate(decision.tradingDate());
        e.setEvaluatedAt(decision.evaluatedAt());
        e.setRegimeType(decision.regimeType());
        e.setMarketGrade(decision.marketGrade());
        e.setTradeAllowed(decision.tradeAllowed());
        e.setRiskMultiplier(decision.riskMultiplier());
        e.setAllowedSetupTypesJson(serializeSetups(decision.allowedSetupTypes()));
        e.setSummary(decision.summary());
        e.setReasonsJson(decision.reasonsJson());
        e.setInputSnapshotJson(decision.inputSnapshotJson());
        e.setMarketSnapshotId(marketSnapshotId);
        e.setVersion(1);
        MarketRegimeDecisionEntity saved = regimeRepository.save(e);
        return toDto(saved);
    }

    static MarketRegimeDecision toDto(MarketRegimeDecisionEntity e) {
        return new MarketRegimeDecision(
                e.getId(),
                e.getTradingDate(),
                e.getEvaluatedAt(),
                e.getRegimeType(),
                e.getMarketGrade(),
                e.isTradeAllowed(),
                e.getRiskMultiplier(),
                parseSetups(e.getAllowedSetupTypesJson()),
                e.getSummary(),
                e.getReasonsJson(),
                e.getInputSnapshotJson()
        );
    }

    // ── helpers ───────────────────────────────────────────────────────

    private JsonNode parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return null;
        try {
            return objectMapper.readTree(payloadJson);
        } catch (JsonProcessingException e) {
            log.debug("[MarketRegimeService] payload_json unparseable, treating as empty: {}",
                    e.getMessage());
            return null;
        }
    }

    private static BigDecimal readDecimal(JsonNode node, String key) {
        if (node == null || !node.hasNonNull(key)) return null;
        try {
            return new BigDecimal(node.get(key).asText());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean readBool(JsonNode node, String key, boolean fallback) {
        if (node == null || !node.hasNonNull(key)) return fallback;
        return node.get(key).asBoolean(fallback);
    }

    private String serializeSetups(List<String> setups) {
        ArrayNode arr = objectMapper.createArrayNode();
        if (setups != null) setups.forEach(arr::add);
        try {
            return objectMapper.writeValueAsString(arr);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private static List<String> parseSetups(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode n = new ObjectMapper().readTree(json);
            if (!n.isArray()) return List.of();
            List<String> out = new java.util.ArrayList<>(n.size());
            for (JsonNode item : n) out.add(item.asText());
            return out;
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
