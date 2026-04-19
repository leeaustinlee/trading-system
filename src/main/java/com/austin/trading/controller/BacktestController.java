package com.austin.trading.controller;

import com.austin.trading.entity.BacktestRunEntity;
import com.austin.trading.entity.BacktestTradeEntity;
import com.austin.trading.service.BacktestService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/backtest")
public class BacktestController {

    private final BacktestService backtestService;

    public BacktestController(BacktestService backtestService) {
        this.backtestService = backtestService;
    }

    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestBody Map<String, String> body) {
        LocalDate start = LocalDate.parse(body.get("startDate"));
        LocalDate end = LocalDate.parse(body.get("endDate"));
        String name = body.get("runName");
        String notes = body.get("notes");
        BacktestRunEntity run = backtestService.runBacktest(start, end, name, notes);
        return ResponseEntity.ok(run);
    }

    @GetMapping("/runs")
    public List<BacktestRunEntity> getRuns() {
        return backtestService.getAllRuns();
    }

    @GetMapping("/runs/{id}")
    public BacktestRunEntity getRun(@PathVariable Long id) {
        return backtestService.getRun(id);
    }

    @GetMapping("/runs/{id}/trades")
    public List<BacktestTradeEntity> getTrades(@PathVariable Long id) {
        return backtestService.getTrades(id);
    }
}
