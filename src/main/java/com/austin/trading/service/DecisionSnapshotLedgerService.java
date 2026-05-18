package com.austin.trading.service;

import com.austin.trading.dto.response.DecisionSnapshotLedgerResponse;
import com.austin.trading.dto.response.FinalDecisionResponse;
import com.austin.trading.dto.response.FinalDecisionSelectedStockResponse;
import com.austin.trading.entity.DecisionSnapshotLedgerEntity;
import com.austin.trading.entity.FinalDecisionEntity;
import com.austin.trading.repository.DecisionSnapshotLedgerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class DecisionSnapshotLedgerService {

    private static final Logger log = LoggerFactory.getLogger(DecisionSnapshotLedgerService.class);

    private final DecisionSnapshotLedgerRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNewTx;

    public DecisionSnapshotLedgerService(DecisionSnapshotLedgerRepository repository,
                                         ObjectMapper objectMapper,
                                         PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Schedules the side-effect-only snapshot writer after the surrounding final_decision transaction commits.
     * If there is no active transaction, writes immediately through the safe REQUIRES_NEW path.
     */
    public void scheduleCreateAfterCommit(FinalDecisionEntity finalDecision,
                                          FinalDecisionResponse response,
                                          FinalDecisionService.AiReadiness readiness,
                                          String preferTaskType) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            safeCreateFromFinalDecision(finalDecision, response, readiness, preferTaskType);
            return;
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            Long finalDecisionId = finalDecision == null ? null : finalDecision.getId();
            log.warn("[DecisionSnapshotLedger] snapshot write skipped finalDecisionId={}: active transaction has no synchronization",
                    finalDecisionId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeCreateFromFinalDecision(finalDecision, response, readiness, preferTaskType);
            }
        });
    }

    /**
     * Side-effect-only snapshot writer. Any failure is isolated in a REQUIRES_NEW transaction and swallowed.
     */
    public Optional<DecisionSnapshotLedgerResponse> safeCreateFromFinalDecision(FinalDecisionEntity finalDecision,
                                                                                 FinalDecisionResponse response,
                                                                                 FinalDecisionService.AiReadiness readiness,
                                                                                 String preferTaskType) {
        try {
            DecisionSnapshotLedgerEntity saved = requiresNewTx.execute(status -> repository.save(
                    buildEntity(finalDecision, response, readiness, preferTaskType)));
            return Optional.ofNullable(saved).map(DecisionSnapshotLedgerResponse::from);
        } catch (Exception e) {
            Long finalDecisionId = finalDecision == null ? null : finalDecision.getId();
            log.warn("[DecisionSnapshotLedger] snapshot write skipped finalDecisionId={}: {}",
                    finalDecisionId, e.getMessage());
            return Optional.empty();
        }
    }

    DecisionSnapshotLedgerEntity buildEntity(FinalDecisionEntity finalDecision,
                                             FinalDecisionResponse response,
                                             FinalDecisionService.AiReadiness readiness,
                                             String preferTaskType) {
        if (finalDecision == null) {
            throw new IllegalArgumentException("finalDecision must not be null");
        }
        DecisionSnapshotLedgerEntity entity = new DecisionSnapshotLedgerEntity();
        entity.setFinalDecisionId(finalDecision.getId());
        entity.setTradingDate(finalDecision.getTradingDate());
        entity.setSourceTaskType(finalDecision.getSourceTaskType());
        entity.setPreferTaskType(blankToNull(preferTaskType));
        entity.setAiTaskId(finalDecision.getAiTaskId());
        entity.setAiStatus(finalDecision.getAiStatus());
        entity.setAiReadinessMode(readiness != null && readiness.mode() != null ? readiness.mode().name() : finalDecision.getAiStatus());
        entity.setFallbackReason(finalDecision.getFallbackReason());
        entity.setFinalDecisionCode(finalDecision.getDecision());
        entity.setResponsePayloadJson(finalDecision.getPayloadJson());

        if (response != null) {
            List<String> selectedSymbols = response.selectedStocks() == null ? List.of() : response.selectedStocks().stream()
                    .map(FinalDecisionSelectedStockResponse::stockCode)
                    .filter(s -> s != null && !s.isBlank())
                    .toList();
            entity.setSelectedSymbolsJson(toJson(selectedSymbols));
            entity.setMergedSymbolsJson(toJson(selectedSymbols));
            // This column is specifically for symbols. rejectedReasons are preserved in responsePayloadJson/gateTraceJson,
            // but must not be mislabeled as rejected symbols.
            entity.setRejectedSymbolsJson(toJson(List.of()));
            entity.setWatchSymbolsJson("WATCH".equalsIgnoreCase(response.decision()) ? toJson(selectedSymbols) : toJson(List.of()));
        }

        extractExistingPayloadSlices(entity, finalDecision.getPayloadJson());
        return entity;
    }

    private void extractExistingPayloadSlices(DecisionSnapshotLedgerEntity entity, String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return;
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            JsonNode candidates = findFirstExisting(root,
                    "candidates", "candidateUniverse", "candidate_universe", "scoredCandidates", "scored_candidates",
                    "primaryCandidates", "primary_candidates", "backupCandidates", "backup_candidates");
            if (candidates != null) {
                entity.setCandidateUniverseJson(objectMapper.writeValueAsString(candidates));
                entity.setCandidateScoresJson(extractCandidateScores(candidates));
            }
            JsonNode planning = root.path("planning");
            JsonNode decisionTrace = planning.path("decisionTrace");
            if (!decisionTrace.isMissingNode()) {
                String decisionTraceJson = objectMapper.writeValueAsString(decisionTrace);
                entity.setDecisionTraceJson(decisionTraceJson);
                entity.setGateTraceJson(decisionTraceJson);
                entity.setMarketContextJson(extractMarketContext(decisionTrace));
            }
        } catch (Exception e) {
            log.warn("[DecisionSnapshotLedger] payload slice extraction skipped: {}", e.getMessage());
        }
    }

    private JsonNode findFirstExisting(JsonNode root, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = root.path(fieldName);
            if (!value.isMissingNode() && !value.isNull()) {
                return value;
            }
            JsonNode planningValue = root.path("planning").path(fieldName);
            if (!planningValue.isMissingNode() && !planningValue.isNull()) {
                return planningValue;
            }
        }
        return null;
    }

    private String extractCandidateScores(JsonNode selectedStocks) throws Exception {
        if (!selectedStocks.isArray()) return null;
        List<Map<String, JsonNode>> scores = new java.util.ArrayList<>();
        for (JsonNode node : selectedStocks) {
            Map<String, JsonNode> score = new LinkedHashMap<>();
            putIfPresent(score, "stockCode", node.get("stockCode"));
            putIfPresent(score, "javaStructureScore", node.get("javaStructureScore"));
            putIfPresent(score, "claudeScore", node.get("claudeScore"));
            putIfPresent(score, "codexScore", node.get("codexScore"));
            putIfPresent(score, "aiWeightedScore", node.get("aiWeightedScore"));
            putIfPresent(score, "consensusScore", node.get("consensusScore"));
            putIfPresent(score, "finalRankScore", node.get("finalRankScore"));
            putIfPresent(score, "disagreementPenalty", node.get("disagreementPenalty"));
            putIfPresent(score, "veto", node.get("veto"));
            if (!score.isEmpty()) scores.add(score);
        }
        return scores.isEmpty() ? null : objectMapper.writeValueAsString(scores);
    }

    private String extractMarketContext(JsonNode decisionTrace) throws Exception {
        Map<String, JsonNode> market = new LinkedHashMap<>();
        putIfPresent(market, "marketGrade", decisionTrace.get("marketGrade"));
        putIfPresent(market, "decisionLock", decisionTrace.get("decisionLock"));
        putIfPresent(market, "timeDecay", decisionTrace.get("timeDecay"));
        putIfPresent(market, "marketRegime", decisionTrace.get("marketRegime"));
        putIfPresent(market, "planningMode", decisionTrace.get("planningMode"));
        return market.isEmpty() ? null : objectMapper.writeValueAsString(market);
    }

    private void putIfPresent(Map<String, JsonNode> target, String key, JsonNode value) {
        if (value != null && !value.isMissingNode() && !value.isNull()) {
            target.put(key, value);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("[DecisionSnapshotLedger] json serialization skipped: {}", e.getMessage());
            return null;
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public List<DecisionSnapshotLedgerResponse> getRecent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return repository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, safeLimit))
                .stream().map(DecisionSnapshotLedgerResponse::from).toList();
    }

    public List<DecisionSnapshotLedgerResponse> getByFinalDecisionId(Long finalDecisionId) {
        return repository.findByFinalDecisionIdOrderByCreatedAtDescIdDesc(finalDecisionId)
                .stream().map(DecisionSnapshotLedgerResponse::from).toList();
    }

    public Optional<DecisionSnapshotLedgerResponse> getById(Long id) {
        return repository.findById(id).map(DecisionSnapshotLedgerResponse::from);
    }
}
