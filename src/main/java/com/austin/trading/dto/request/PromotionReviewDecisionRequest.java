package com.austin.trading.dto.request;

public record PromotionReviewDecisionRequest(
        String status,
        String reviewer,
        String reason
) {}
