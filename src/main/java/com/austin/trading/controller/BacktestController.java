package com.austin.trading.controller;

import com.austin.trading.entity.BacktestRunEntity;
import com.austin.trading.entity.BacktestTradeEntity;
import com.austin.trading.dto.response.RrRootCauseDiagnosisResponse;
import com.austin.trading.service.BacktestService;
import com.austin.trading.service.RrRootCauseDiagnosisService;
import com.austin.trading.service.RrShadowValidationService;
import com.austin.trading.service.RrValidationCoverageRepairService;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final RrShadowValidationService rrShadowValidationService;
    private final RrValidationCoverageRepairService rrValidationCoverageRepairService;

    @Autowired
    public BacktestController(BacktestService backtestService,
                              RrRootCauseDiagnosisService rrRootCauseDiagnosisService,
                              RrShadowValidationService rrShadowValidationService,
                              RrValidationCoverageRepairService rrValidationCoverageRepairService) {
        this.backtestService = backtestService;
        this.rrRootCauseDiagnosisService = rrRootCauseDiagnosisService;
        this.rrShadowValidationService = rrShadowValidationService;
        this.rrValidationCoverageRepairService = rrValidationCoverageRepairService;
    }

    public BacktestController(BacktestService backtestService,
                              RrRootCauseDiagnosisService rrRootCauseDiagnosisService,
                              RrShadowValidationService rrShadowValidationService) {
        this(backtestService, rrRootCauseDiagnosisService, rrShadowValidationService, null);
    }

    public BacktestController(BacktestService backtestService,
                              RrRootCauseDiagnosisService rrRootCauseDiagnosisService) {
        this(backtestService, rrRootCauseDiagnosisService, null, null);
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

    @PostMapping("/diagnosis/rr-shadow-validation/backfill")
    public Map<String, Object> rrShadowValidationBackfill(@RequestParam(defaultValue = "60") int days) {
        if (rrShadowValidationService == null) {
            throw new IllegalStateException("RR shadow validation service is not available");
        }
        return rrShadowValidationService.backfill(days);
    }

    @PostMapping("/diagnosis/rr-shadow-validation/backfill-expanded")
    public Map<String, Object> rrShadowValidationBackfillExpanded(@RequestParam(defaultValue = "180") int days) {
        if (rrShadowValidationService == null) {
            throw new IllegalStateException("RR shadow validation service is not available");
        }
        return rrShadowValidationService.backfillExpanded(days);
    }

    @GetMapping("/diagnosis/rr-shadow-validation/summary")
    public RrShadowValidationService.Summary rrShadowValidationSummary(@RequestParam(defaultValue = "60") int days) {
        if (rrShadowValidationService == null) {
            throw new IllegalStateException("RR shadow validation service is not available");
        }
        return rrShadowValidationService.summary(days);
    }

    @PostMapping("/diagnosis/rr-shadow-validation/repair-coverage")
    public RrValidationCoverageRepairService.RepairResponse rrShadowValidationRepairCoverage(
            @RequestParam(defaultValue = "60") int days) {
        if (rrValidationCoverageRepairService == null) {
            throw new IllegalStateException("RR validation coverage repair service is not available");
        }
        return rrValidationCoverageRepairService.repairCoverage(days);
    }
}
