package com.austin.trading.controller;

import com.austin.trading.entity.TradeReviewEntity;
import com.austin.trading.service.TradeReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trade-reviews")
public class TradeReviewController {

    private final TradeReviewService tradeReviewService;

    public TradeReviewController(TradeReviewService tradeReviewService) {
        this.tradeReviewService = tradeReviewService;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate() {
        int count = tradeReviewService.generateForAllUnreviewed();
        return ResponseEntity.ok(Map.of("generated", count));
    }

    @GetMapping
    public List<TradeReviewEntity> getAll() {
        return tradeReviewService.getAll();
    }

    @GetMapping("/{positionId}")
    public List<TradeReviewEntity> getByPosition(@PathVariable Long positionId) {
        return tradeReviewService.getByPositionId(positionId);
    }
}
