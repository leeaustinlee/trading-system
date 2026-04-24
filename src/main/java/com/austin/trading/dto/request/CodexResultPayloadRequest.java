package com.austin.trading.dto.request;

import java.util.List;

public record CodexResultPayloadRequest(
        String taskType,
        String marketSession,
        String reviewTime,
        String marketBias,
        String marketRegime,
        Boolean noTradeDecision,
        List<CodexReviewedSymbolRequest> selected,
        List<CodexReviewedSymbolRequest> watchlist,
        List<CodexReviewedSymbolRequest> rejected
) {
}
