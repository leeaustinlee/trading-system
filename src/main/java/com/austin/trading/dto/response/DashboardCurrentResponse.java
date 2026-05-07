package com.austin.trading.dto.response;

public record DashboardCurrentResponse(
        MarketCurrentResponse market,
        TradingStateResponse tradingState,
        FinalDecisionRecordResponse finalDecision,
        HourlyGateDecisionRecordResponse hourlyGateDecision,
        MonitorDecisionRecordResponse monitorDecision,
        NotificationResponse latestNotification,
        java.util.List<CandidateResponse> candidates,
        String marketState,
        String monitorState,
        String tradeDecision,
        String finalDecisionCode
) {
    public DashboardCurrentResponse(
            MarketCurrentResponse market,
            TradingStateResponse tradingState,
            FinalDecisionRecordResponse finalDecision,
            HourlyGateDecisionRecordResponse hourlyGateDecision,
            MonitorDecisionRecordResponse monitorDecision,
            NotificationResponse latestNotification,
            java.util.List<CandidateResponse> candidates
    ) {
        this(market, tradingState, finalDecision, hourlyGateDecision, monitorDecision,
                latestNotification, candidates, null, null, null, null);
    }
}
