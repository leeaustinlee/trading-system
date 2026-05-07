package com.austin.trading.controller;

import com.austin.trading.dto.response.DashboardCurrentResponse;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.FinalDecisionService;
import com.austin.trading.service.HourlyGateDecisionService;
import com.austin.trading.service.MarketDataService;
import com.austin.trading.service.MonitorDecisionService;
import com.austin.trading.service.NotificationService;
import com.austin.trading.service.TradingStateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final MarketDataService marketDataService;
    private final TradingStateService tradingStateService;
    private final FinalDecisionService finalDecisionService;
    private final HourlyGateDecisionService hourlyGateDecisionService;
    private final MonitorDecisionService monitorDecisionService;
    private final NotificationService notificationService;
    private final CandidateScanService candidateScanService;

    public DashboardController(
            MarketDataService marketDataService,
            TradingStateService tradingStateService,
            FinalDecisionService finalDecisionService,
            HourlyGateDecisionService hourlyGateDecisionService,
            MonitorDecisionService monitorDecisionService,
            NotificationService notificationService,
            CandidateScanService candidateScanService
    ) {
        this.marketDataService = marketDataService;
        this.tradingStateService = tradingStateService;
        this.finalDecisionService = finalDecisionService;
        this.hourlyGateDecisionService = hourlyGateDecisionService;
        this.monitorDecisionService = monitorDecisionService;
        this.notificationService = notificationService;
        this.candidateScanService = candidateScanService;
    }

    @GetMapping("/current")
    public DashboardCurrentResponse getCurrentDashboard() {
        var market = marketDataService.getMarketPreferToday().orElse(null);
        var tradingState = tradingStateService.getCurrentState().orElse(null);
        var finalDecision = finalDecisionService.getCurrent().orElse(null);
        var hourlyGate = hourlyGateDecisionService.getCurrent().orElse(null);
        var monitor = monitorDecisionService.getCurrent().orElse(null);
        String finalCode = finalDecision != null ? finalDecision.decision() : null;
        String marketState = marketState(market);
        String monitorState = monitorState(tradingState, monitor);
        String tradeDecision = "REST".equalsIgnoreCase(finalCode) ? "REST" : safeDecision(finalCode);
        return new DashboardCurrentResponse(
                market,
                tradingState,
                finalDecision,
                hourlyGate,
                monitor,
                notificationService.getLatestNotification().orElse(null),
                candidateScanService.getCurrentCandidates(5),
                marketState,
                monitorState,
                tradeDecision,
                finalCode
        );
    }

    private String marketState(com.austin.trading.dto.response.MarketCurrentResponse market) {
        if (market == null) return "UNKNOWN";
        if ("A".equalsIgnoreCase(market.marketGrade()) || "B".equalsIgnoreCase(market.marketGrade())) return "BULLISH";
        if ("C".equalsIgnoreCase(market.marketGrade())) return "RISK_OFF";
        return "NEUTRAL";
    }

    private String monitorState(com.austin.trading.dto.response.TradingStateResponse state,
                                com.austin.trading.dto.response.MonitorDecisionRecordResponse monitor) {
        if (monitor != null && monitor.monitorMode() != null) return monitor.monitorMode();
        if (state != null && state.monitorMode() != null) return state.monitorMode();
        return "UNKNOWN";
    }

    private String safeDecision(String finalCode) {
        if (finalCode == null || finalCode.isBlank()) return "UNKNOWN";
        return finalCode;
    }
}
