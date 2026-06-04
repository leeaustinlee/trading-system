package com.austin.trading.dto.response;

import java.time.LocalDateTime;
import java.util.List;

public record NarrativeThesisResponse(
        LocalDateTime evaluatedAt,
        boolean readOnly,
        boolean productionDecisionAllowed,
        boolean autoBuyEnabled,
        boolean autoSellEnabled,
        boolean manualConfirmRequired,
        int positionCount,
        List<PositionThesisLedgerResponse.Item> items
) {
    public static NarrativeThesisResponse of(List<PositionThesisLedgerResponse.Item> items) {
        return new NarrativeThesisResponse(LocalDateTime.now(), true, false, false, false, true,
                items == null ? 0 : items.size(), items == null ? List.of() : items);
    }
}
