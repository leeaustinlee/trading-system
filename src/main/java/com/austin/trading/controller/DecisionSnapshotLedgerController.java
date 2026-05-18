package com.austin.trading.controller;

import com.austin.trading.dto.response.DecisionSnapshotLedgerResponse;
import com.austin.trading.service.DecisionSnapshotLedgerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/decision-snapshots")
public class DecisionSnapshotLedgerController {

    private final DecisionSnapshotLedgerService service;

    public DecisionSnapshotLedgerController(DecisionSnapshotLedgerService service) {
        this.service = service;
    }

    @GetMapping("/recent")
    public List<DecisionSnapshotLedgerResponse> recent(@RequestParam(defaultValue = "20") int limit) {
        return service.getRecent(limit);
    }

    @GetMapping("/final-decision/{finalDecisionId}")
    public List<DecisionSnapshotLedgerResponse> byFinalDecision(@PathVariable Long finalDecisionId) {
        return service.getByFinalDecisionId(finalDecisionId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DecisionSnapshotLedgerResponse> byId(@PathVariable Long id) {
        return service.getById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
