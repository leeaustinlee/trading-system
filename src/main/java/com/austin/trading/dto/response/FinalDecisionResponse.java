package com.austin.trading.dto.response;

import java.util.List;

public record FinalDecisionResponse(
        String decision,
        List<FinalDecisionSelectedStockResponse> selectedStocks,
        List<String> rejectedReasons,
        String summary
) {
}
