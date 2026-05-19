package com.austin.trading.controller;

import com.austin.trading.entity.BacktestRunEntity;
import com.austin.trading.entity.BacktestTradeEntity;
import com.austin.trading.dto.response.RrRootCauseDiagnosisResponse;
import com.austin.trading.service.BacktestService;
import com.austin.trading.service.P0BacktestDiagnosisService;
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
    private final P0BacktestDiagnosisService p0BacktestDiagnosisService;

    @Autowired
    public BacktestController(BacktestService backtestService,
                              RrRootCauseDiagnosisService rrRootCauseDiagnosisService,
                              RrShadowValidationService rrShadowValidationService,
                              RrValidationCoverageRepairService rrValidationCoverageRepairService,
                              P0BacktestDiagnosisService p0BacktestDiagnosisService) {
        this.backtestService = backtestService;
        this.rrRootCauseDiagnosisService = rrRootCauseDiagnosisService;
        this.rrShadowValidationService = rrShadowValidationService;
        this.rrValidationCoverageRepairService = rrValidationCoverageRepairService;
        this.p0BacktestDiagnosisService = p0BacktestDiagnosisService;
    }

    public BacktestController(BacktestService backtestService,
                              RrRootCauseDiagnosisService rrRootCauseDiagnosisService,
                              RrShadowValidationService rrShadowValidationService,
                              RrValidationCoverageRepairService rrValidationCoverageRepairService) {
        this(backtestService, rrRootCauseDiagnosisService, rrShadowValidationService, rrValidationCoverageRepairService, null);
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

    @GetMapping("/diagnosis/price-plan-sanity")
    public Map<String, Object> pricePlanSanity(@RequestParam(defaultValue = "60") int days) {
        if (p0BacktestDiagnosisService == null) {
            throw new IllegalStateException("P0 diagnosis service is not available");
        }
        return p0BacktestDiagnosisService.pricePlanSanity(days);
    }

    @GetMapping("/diagnosis/theme-propagation")
    public Map<String, Object> themePropagation(@RequestParam(defaultValue = "60") int days) {
        if (p0BacktestDiagnosisService == null) {
            throw new IllegalStateException("P0 diagnosis service is not available");
        }
        return p0BacktestDiagnosisService.themePropagation(days);
    }

    @GetMapping("/diagnosis/exit-rule-comparison")
    public Map<String, Object> exitRuleComparison(@RequestParam(defaultValue = "60") int days) {
        if (p0BacktestDiagnosisService == null) {
            throw new IllegalStateException("P0 diagnosis service is not available");
        }
        return p0BacktestDiagnosisService.exitRuleComparison(days);
    }

    @GetMapping("/diagnosis/exit-rule-cases")
    public Map<String, Object> exitRuleCases(@RequestParam(defaultValue = "60") int days) {
        if (p0BacktestDiagnosisService == null) {
            throw new IllegalStateException("P0 diagnosis service is not available");
        }
        return p0BacktestDiagnosisService.exitRuleCaseTable(days);
    }

    @PostMapping("/diagnosis/exit-rule-shadow-validation/backfill")
    public Map<String, Object> exitRuleShadowValidationBackfill(@RequestParam(defaultValue = "60") int days) {
        if (p0BacktestDiagnosisService == null) {
            throw new IllegalStateException("P0 diagnosis service is not available");
        }
        return p0BacktestDiagnosisService.backfillExitRuleShadowValidation(days);
    }

    @GetMapping("/diagnosis/exit-rule-shadow-validation/summary")
    public Map<String, Object> exitRuleShadowValidationSummary(@RequestParam(defaultValue = "30") int days) {
        if (p0BacktestDiagnosisService == null) {
            throw new IllegalStateException("P0 diagnosis service is not available");
        }
        return p0BacktestDiagnosisService.exitRuleShadowValidationSummary(days);
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
