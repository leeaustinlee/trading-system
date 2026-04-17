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
        return new DashboardCurrentResponse(
                marketDataService.getCurrentMarket().orElse(null),
                tradingStateService.getCurrentState().orElse(null),
                finalDecisionService.getCurrent().orElse(null),
                hourlyGateDecisionService.getCurrent().orElse(null),
                monitorDecisionService.getCurrent().orElse(null),
                notificationService.getLatestNotification().orElse(null),
                candidateScanService.getCurrentCandidates(5)
        );
    }
}
