package com.austin.trading.controller;

import com.austin.trading.service.StructuralExitReplayAnalysisService;
import com.austin.trading.service.StructuralExitReplayBackfillService;
import com.austin.trading.service.StructuralExitReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/structural-exit")
public class StructuralExitReportController {
    private final StructuralExitReportService service;
    private final StructuralExitReplayBackfillService replayBackfillService;
    private final StructuralExitReplayAnalysisService replayAnalysisService;

    public StructuralExitReportController(StructuralExitReportService service,
                                          StructuralExitReplayBackfillService replayBackfillService,
                                          StructuralExitReplayAnalysisService replayAnalysisService) {
        this.service = service;
        this.replayBackfillService = replayBackfillService;
        this.replayAnalysisService = replayAnalysisService;
    }

    @GetMapping("/summary")
    public Map<String,Object> summary() { return service.summary(); }

    @PostMapping("/replay/backfill")
    public StructuralExitReplayBackfillService.BackfillSummary backfillReplay(
            @RequestParam(defaultValue = "60") int days,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return replayBackfillService.backfillLastDays(asOf == null ? LocalDate.now() : asOf, days);
    }

    @GetMapping("/replay/analysis")
    public StructuralExitReplayAnalysisService.ReplayKpiReport replayAnalysis(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(59) : from;
        return replayAnalysisService.analyze(start, end);
    }
}
