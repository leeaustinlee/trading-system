package com.austin.trading.controller;

import com.austin.trading.dto.response.AdaptiveExitReviewResponse;
import com.austin.trading.service.AdaptiveExitReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/adaptive-exit-review")
public class AdaptiveExitReviewController {
    private final AdaptiveExitReviewService service;

    public AdaptiveExitReviewController(AdaptiveExitReviewService service) {
        this.service = service;
    }

    @GetMapping("/open-positions")
    public ResponseEntity<AdaptiveExitReviewResponse> openPositions() {
        return ResponseEntity.ok(service.reviewOpenPositions());
    }

    @GetMapping("/{symbol}")
    public ResponseEntity<AdaptiveExitReviewResponse> symbol(@PathVariable String symbol) {
        return ResponseEntity.ok(service.reviewSymbol(symbol));
    }
}
