package com.austin.trading.dto.response;

import java.util.List;
import java.util.Map;

/**
 * Final decision response.
 *
 * <p>v2.8: 新增 {@code planningPayload} 給盤後規劃模式用
 * （含 primaryCandidates / backupCandidates / sectorIndicators / avoidSymbols /
 * tomorrowExecutionNotes）。盤中進場模式此欄位為 {@code null}。</p>
 */
public record FinalDecisionResponse(
        String decision,
        List<FinalDecisionSelectedStockResponse> selectedStocks,
        List<String> rejectedReasons,
        String summary,
        Map<String, Object> planningPayload
) {
    /** Legacy 4-arg constructor（向下相容；盤中模式 planningPayload=null）。 */
    public FinalDecisionResponse(String decision,
                                  List<FinalDecisionSelectedStockResponse> selectedStocks,
                                  List<String> rejectedReasons,
                                  String summary) {
        this(decision, selectedStocks, rejectedReasons, summary, null);
    }
}
