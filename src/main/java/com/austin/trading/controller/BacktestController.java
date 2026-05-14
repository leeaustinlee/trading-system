package com.austin.trading.controller;

import com.austin.trading.entity.BacktestRunEntity;
import com.austin.trading.entity.BacktestTradeEntity;
import com.austin.trading.dto.response.RrRootCauseDiagnosisResponse;
import com.austin.trading.service.BacktestService;
import com.austin.trading.service.RrRootCauseDiagnosisService;
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
    private final RrRootCauseDiagnosisService rrRootCauseDiagnosisService;

    public BacktestController(BacktestService backtestService,
                              RrRootCauseDiagnosisService rrRootCauseDiagnosisService) {
        this.backtestService = backtestService;
        this.rrRootCauseDiagnosisService = rrRootCauseDiagnosisService;
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

    @GetMapping("/diagnosis/recent")
    public Map<String, Object> recentDiagnosis(@RequestParam(defaultValue = "30") int days) {
        return backtestService.recentDiagnosis(days);
    }

    @GetMapping("/diagnosis/rr-root-cause")
    public RrRootCauseDiagnosisResponse rrRootCauseDiagnosis(@RequestParam(defaultValue = "60") int days) {
        return rrRootCauseDiagnosisService.diagnose(days);
    }
}
