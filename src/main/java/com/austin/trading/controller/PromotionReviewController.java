package com.austin.trading.controller;

import com.austin.trading.dto.request.PromotionReviewDecisionRequest;
import com.austin.trading.dto.response.PromotionReviewResponse;
import com.austin.trading.service.PromotionReviewService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/promotion-review")
public class PromotionReviewController {
    private final PromotionReviewService service;

    public PromotionReviewController(PromotionReviewService service) {
        this.service = service;
    }

    @PostMapping("/build")
    public PromotionReviewResponse build(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.build(date);
    }

    @GetMapping("/queue")
    public PromotionReviewResponse queue(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.queue(date);
    }

    @GetMapping("/item/{id}")
    public PromotionReviewResponse.Item item(@PathVariable Long id) {
        return service.item(id);
    }

    @PostMapping("/item/{id}/decision")
    public PromotionReviewResponse.Item decide(@PathVariable Long id, @RequestBody PromotionReviewDecisionRequest request) {
        return service.decide(id, request);
    }

    @GetMapping("/audit")
    public PromotionReviewResponse.AuditResponse audit(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String symbol) {
        return service.audit(date, symbol);
    }
}
