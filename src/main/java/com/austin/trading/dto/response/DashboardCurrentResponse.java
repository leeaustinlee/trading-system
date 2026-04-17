package com.austin.trading.dto.response;

public record DashboardCurrentResponse(
        MarketCurrentResponse market,
        TradingStateResponse tradingState,
        FinalDecisionRecordResponse finalDecision,
        HourlyGateDecisionRecordResponse hourlyGateDecision,
        MonitorDecisionRecordResponse monitorDecision,
        NotificationResponse latestNotification,
        java.util.List<CandidateResponse> candidates
) {
}
