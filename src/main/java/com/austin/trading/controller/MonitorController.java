package com.austin.trading.controller;

import com.austin.trading.dto.request.TradingStateUpsertRequest;
import com.austin.trading.dto.response.MonitorDecisionRecordResponse;
import com.austin.trading.dto.response.TradingStateResponse;
import com.austin.trading.service.MonitorDecisionService;
import com.austin.trading.service.TradingStateService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final TradingStateService tradingStateService;
    private final MonitorDecisionService monitorDecisionService;

    public MonitorController(TradingStateService tradingStateService, MonitorDecisionService monitorDecisionService) {
        this.tradingStateService = tradingStateService;
        this.monitorDecisionService = monitorDecisionService;
    }

    @GetMapping("/current")
    public ResponseEntity<TradingStateResponse> getCurrent() {
        return tradingStateService.getCurrentState()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/history")
    public List<TradingStateResponse> getHistory(@RequestParam(defaultValue = "50") int limit) {
        return tradingStateService.getHistory(limit);
    }

    @GetMapping("/decisions/current")
    public ResponseEntity<MonitorDecisionRecordResponse> getCurrentMonitorDecision() {
        return monitorDecisionService.getCurrent()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/decisions/history")
    public List<MonitorDecisionRecordResponse> getMonitorDecisionHistory(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String date) {
        if ("today".equalsIgnoreCase(date)) {
            return monitorDecisionService.getHistoryByDate(LocalDate.now(), limit);
        }
        if (date != null && !date.isBlank()) {
            return monitorDecisionService.getHistoryByDate(LocalDate.parse(date), limit);
        }
        return monitorDecisionService.getHistory(limit);
    }

    @PostMapping("/state")
    public TradingStateResponse createState(@Valid @RequestBody TradingStateUpsertRequest request) {
        return tradingStateService.create(request);
    }
}
