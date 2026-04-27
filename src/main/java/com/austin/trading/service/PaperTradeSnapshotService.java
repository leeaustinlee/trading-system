package com.austin.trading.service;

import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.entity.PaperTradeSnapshotEntity;
import com.austin.trading.repository.PaperTradeRepository;
import com.austin.trading.repository.PaperTradeSnapshotRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Subagent C — captures full point-in-time decision trace at ENTRY and EXIT for every paper trade,
 * so back-testing / AI calibration can reconstruct exactly what the system saw at decision time.
 *
 * <p>Two snapshot types per trade:</p>
 * <ul>
 *   <li>{@code ENTRY} — written when {@link PaperTradeService#onFinalDecisionPersisted} opens a row.</li>
 *   <li>{@code EXIT}  — written when {@link PaperTradeService#runAutoExitCycle} closes a row.</li>
 * </ul>
 *
 * <p>Payload JSON schema v1.0 keys (top level):</p>
 * <pre>
 *   ENTRY: {
 *     paperTradeId, finalDecisionId, decisionCode, tradingDate, symbol,
 *     entryPrice, intendedEntryPrice, simulatedEntryPrice, entryGrade, entryRrRatio,
 *     stopLossPrice, target1Price, target2Price,
 *     finalRankScore, themeTag, themeHeatScore, regime,
 *     entryPayloadJsonRaw  // optional, full original entry_payload_json
 *   }
 *   EXIT: {
 *     paperTradeId, exitPrice, simulatedExitPrice, exitReason, holdingDays,
 *     pnlPct, pnlAmount, mfePct, maePct,
 *     latestReviewStatus, latestReviewReason
 *   }
 * </pre>
 *
 * <p>Gated by {@code trading.paper_mode.enabled}; noop when disabled.</p>
 */
@Service
public class PaperTradeSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(PaperTradeSnapshotService.class);
    public static final String SCHEMA_VERSION = "v1.0";
    public static final String TYPE_ENTRY = "ENTRY";
    public static final String TYPE_EXIT  = "EXIT";

    private final PaperTradeSnapshotRepository repo;
    private final PaperTradeRepository paperTradeRepo;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ScoreConfigService> scoreConfigProvider;

    public PaperTradeSnapshotService(PaperTradeSnapshotRepository repo,
                                     PaperTradeRepository paperTradeRepo,
                                     ObjectMapper objectMapper,
                                     ObjectProvider<ScoreConfigService> scoreConfigProvider) {
        this.repo = repo;
        this.paperTradeRepo = paperTradeRepo;
        this.objectMapper = objectMapper;
        this.scoreConfigProvider = scoreConfigProvider;
    }

    boolean isPaperModeEnabled() {
        ScoreConfigService cfg = scoreConfigProvider != null ? scoreConfigProvider.getIfAvailable() : null;
        if (cfg == null) return true;
        return cfg.getBoolean("trading.paper_mode.enabled", true);
    }

    @Transactional
    public PaperTradeSnapshotEntity recordEntrySnapshot(PaperTradeEntity trade) {
        if (!isPaperModeEnabled()) {
            log.debug("[PaperTradeSnapshot] paper_mode disabled, skip ENTRY id={}", trade != null ? trade.getId() : null);
            return null;
        }
        if (trade == null || trade.getId() == null) return null;
        // Idempotency: if ENTRY snapshot already exists, return it (don't duplicate).
        return repo.findTopByPaperTradeIdAndSnapshotTypeOrderByCapturedAtDesc(trade.getId(), TYPE_ENTRY)
                .orElseGet(() -> {
                    String payload = buildEntryPayload(trade);
                    PaperTradeSnapshotEntity snap = new PaperTradeSnapshotEntity(
                            trade.getId(), TYPE_ENTRY, LocalDateTime.now(), payload, SCHEMA_VERSION);
                    return repo.save(snap);
                });
    }

    @Transactional
    public PaperTradeSnapshotEntity recordExitSnapshot(PaperTradeEntity trade,
                                                       String latestReviewStatus,
                                                       String latestReviewReason) {
        if (!isPaperModeEnabled()) return null;
        if (trade == null || trade.getId() == null) return null;
        return repo.findTopByPaperTradeIdAndSnapshotTypeOrderByCapturedAtDesc(trade.getId(), TYPE_EXIT)
                .orElseGet(() -> {
                    String payload = buildExitPayload(trade, latestReviewStatus, latestReviewReason);
                    PaperTradeSnapshotEntity snap = new PaperTradeSnapshotEntity(
                            trade.getId(), TYPE_EXIT, LocalDateTime.now(), payload, SCHEMA_VERSION);
                    return repo.save(snap);
                });
    }

    /** Lazy backfill: catch trades that closed without an EXIT snapshot (graceful degradation). */
    @Transactional
    public int backfillMissingExitSnapshots() {
        if (!isPaperModeEnabled()) return 0;
        List<PaperTradeEntity> closed = paperTradeRepo.findByStatusOrderByEntryDateAscIdAsc("CLOSED");
        int filled = 0;
        for (PaperTradeEntity t : closed) {
            if (t.getId() == null) continue;
            if (repo.countByPaperTradeIdAndSnapshotType(t.getId(), TYPE_EXIT) > 0) continue;
            recordExitSnapshot(t, null, null);
            filled++;
        }
        if (filled > 0) log.info("[PaperTradeSnapshot] backfilled {} EXIT snapshots", filled);
        return filled;
    }

    public List<PaperTradeSnapshotEntity> findAll(Long paperTradeId) {
        return repo.findByPaperTradeIdOrderByCapturedAtAsc(paperTradeId);
    }

    // ── payload builders ────────────────────────────────────────────────────

    String buildEntryPayload(PaperTradeEntity t) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("paperTradeId", t.getId());
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("snapshotType", TYPE_ENTRY);
        if (t.getFinalDecisionId() != null) root.put("finalDecisionId", t.getFinalDecisionId());
        if (t.getEntryDate() != null)       root.put("tradingDate", t.getEntryDate().toString());
        root.put("symbol", t.getSymbol());
        if (t.getStockName() != null)       root.put("stockName", t.getStockName());
        putBd(root, "entryPrice", t.getEntryPrice());
        putBd(root, "intendedEntryPrice", t.getIntendedEntryPrice());
        putBd(root, "simulatedEntryPrice", t.getSimulatedEntryPrice());
        if (t.getEntryGrade() != null)      root.put("entryGrade", t.getEntryGrade());
        putBd(root, "entryRrRatio", t.getEntryRrRatio());
        if (t.getEntryRegime() != null)     root.put("entryRegime", t.getEntryRegime());
        putBd(root, "stopLossPrice", t.getStopLossPrice());
        putBd(root, "target1Price", t.getTarget1Price());
        putBd(root, "target2Price", t.getTarget2Price());
        putBd(root, "finalRankScore", t.getFinalRankScore());
        if (t.getThemeTag() != null)        root.put("themeTag", t.getThemeTag());
        putBd(root, "themeHeatScore", t.getThemeHeatScore());
        if (t.getStrategyType() != null)    root.put("strategyType", t.getStrategyType());
        if (t.getMaxHoldingDays() != null && t.getMaxHoldingDays() > 0)
            root.put("maxHoldingDays", t.getMaxHoldingDays());

        // raw entry_payload_json (already a JSON string) — embed as nested object if parseable
        if (t.getEntryPayloadJson() != null && !t.getEntryPayloadJson().isBlank()) {
            try {
                JsonNode raw = objectMapper.readTree(t.getEntryPayloadJson());
                root.set("entryPayloadJsonRaw", raw);
            } catch (JsonProcessingException e) {
                root.put("entryPayloadJsonRaw", t.getEntryPayloadJson());
            }
        }
        return writeJson(root);
    }

    String buildExitPayload(PaperTradeEntity t, String latestReviewStatus, String latestReviewReason) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("paperTradeId", t.getId());
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("snapshotType", TYPE_EXIT);
        root.put("symbol", t.getSymbol());
        if (t.getExitDate() != null) root.put("exitDate", t.getExitDate().toString());
        if (t.getExitTime() != null) root.put("exitTime", t.getExitTime().toString());
        putBd(root, "exitPrice", t.getExitPrice());
        putBd(root, "simulatedExitPrice", t.getSimulatedExitPrice());
        if (t.getExitReason() != null) root.put("exitReason", t.getExitReason());
        if (t.getHoldingDays() != null) root.put("holdingDays", t.getHoldingDays());
        putBd(root, "pnlPct", t.getPnlPct());
        putBd(root, "pnlAmount", t.getPnlAmount());
        putBd(root, "mfePct", t.getMfePct());
        putBd(root, "maePct", t.getMaePct());
        if (latestReviewStatus != null) root.put("latestReviewStatus", latestReviewStatus);
        if (latestReviewReason != null) root.put("latestReviewReason", latestReviewReason);
        return writeJson(root);
    }

    private static void putBd(ObjectNode node, String key, BigDecimal v) {
        if (v != null) node.put(key, v);
    }

    private String writeJson(Map<String, ?> map) {
        try { return objectMapper.writeValueAsString(map); }
        catch (JsonProcessingException e) { return "{}"; }
    }
    private String writeJson(ObjectNode n) {
        try { return objectMapper.writeValueAsString(n); }
        catch (JsonProcessingException e) { return "{}"; }
    }
}
