package com.austin.trading.dto.response;

public record MarketGateDecisionResponse(
        String marketGrade,
        String marketPhase,
        String decision,
        boolean allowTrade,
        int score,
        String reason
) {
}
