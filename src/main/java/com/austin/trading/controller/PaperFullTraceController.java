package com.austin.trading.controller;

import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.entity.PaperTradeExitLogEntity;
import com.austin.trading.entity.PaperTradeSnapshotEntity;
import com.austin.trading.repository.PaperTradeExitLogRepository;
import com.austin.trading.repository.PaperTradeRepository;
import com.austin.trading.repository.PaperTradeSnapshotRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Subagent C — single-call trace bundle for one paper trade.
 *
 * <p>{@code GET /api/paper/{id}/full-trace} returns:</p>
 * <pre>
 * {
 *   "paperTrade": { ... },
 *   "snapshots": [ {type, capturedAt, payload}, ... ],
 *   "exitLog":   [ {evaluatedAt, outcome, currentPrice, ...}, ... ]
 * }
 * </pre>
 */
@RestController
@RequestMapping("/api/paper")
public class PaperFullTraceController {

    private final PaperTradeRepository paperTradeRepo;
    private final PaperTradeSnapshotRepository snapshotRepo;
    private final ObjectProvider<PaperTradeExitLogRepository> exitLogRepoProvider;
    private final ObjectMapper objectMapper;

    public PaperFullTraceController(PaperTradeRepository paperTradeRepo,
                                    PaperTradeSnapshotRepository snapshotRepo,
                                    ObjectProvider<PaperTradeExitLogRepository> exitLogRepoProvider,
                                    ObjectMapper objectMapper) {
        this.paperTradeRepo = paperTradeRepo;
        this.snapshotRepo = snapshotRepo;
        this.exitLogRepoProvider = exitLogRepoProvider;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/{id}/full-trace")
    public ResponseEntity<Map<String, Object>> fullTrace(@PathVariable Long id) {
        PaperTradeEntity trade = paperTradeRepo.findById(id).orElse(null);
        if (trade == null) return ResponseEntity.notFound().build();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("paperTrade", trade);

        List<PaperTradeSnapshotEntity> snaps = snapshotRepo.findByPaperTradeIdOrderByCapturedAtAsc(id);
        result.put("snapshots", snaps.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("type", s.getSnapshotType());
            m.put("capturedAt", s.getCapturedAt());
            m.put("schemaVersion", s.getSchemaVersion());
            // Parse JSON to nested object so client doesn't need to JSON.parse twice
            String raw = s.getPayloadJson();
            if (raw != null && !raw.isBlank()) {
                try { m.put("payload", objectMapper.readTree(raw)); }
                catch (JsonProcessingException e) { m.put("payload", raw); }
            }
            return m;
        }).toList());

        PaperTradeExitLogRepository exitLogRepo = exitLogRepoProvider != null
                ? exitLogRepoProvider.getIfAvailable() : null;
        if (exitLogRepo != null) {
            // Repo provides Desc; reverse via stream so client sees chronological order
            List<PaperTradeExitLogEntity> logs = exitLogRepo.findByPaperTradeIdOrderByEvaluatedAtDesc(id);
            List<PaperTradeExitLogEntity> chronological = new java.util.ArrayList<>(logs);
            java.util.Collections.reverse(chronological);
            result.put("exitLog", chronological);
        } else {
            result.put("exitLog", List.of());
        }
        return ResponseEntity.ok(result);
    }
}
