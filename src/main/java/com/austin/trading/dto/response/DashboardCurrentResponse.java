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
        String finalDecisionCode,
        long pendingTuningRecommendationCount,
        StrategyTuningRecommendationDto latestHighConfidenceRecommendation,
        String tuningWarningMessage,
        java.math.BigDecimal tuningSuccessRate,
        TuningEvaluationResultDto lastTuningResult,
        long rollbackSuggestionCount
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
                latestNotification, candidates, null, null, null, null, 0, null, "樣本不足，不建議調參",
                java.math.BigDecimal.ZERO, null, 0);
    }
}
