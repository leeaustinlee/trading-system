package com.austin.trading.controller;

import com.austin.trading.entity.StrategyRecommendationEntity;
import com.austin.trading.service.StrategyRecommendationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/strategy-recommendations")
public class StrategyRecommendationController {

    private final StrategyRecommendationService service;

    public StrategyRecommendationController(StrategyRecommendationService service) {
        this.service = service;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestParam(required = false) Long sourceRunId) {
        var recs = service.generate(sourceRunId);
        return ResponseEntity.ok(Map.of("generated", recs.size(), "recommendations", recs));
    }

    @GetMapping
    public List<StrategyRecommendationEntity> getAll() {
        return service.getAll();
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String status = body.get("status");
        if (status == null) return ResponseEntity.badRequest().body(Map.of("error", "status required"));
        return ResponseEntity.ok(service.updateStatus(id, status));
    }
}
