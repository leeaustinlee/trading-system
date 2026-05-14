package com.austin.trading.controller;

import com.austin.trading.service.CandidateForwardTrackingService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/forward-tracking")
public class CandidateForwardTrackingController {
    private final CandidateForwardTrackingService service;
    private final com.austin.trading.service.CandidateForwardReturnBackfillService returnBackfillService;
    public CandidateForwardTrackingController(CandidateForwardTrackingService service,
                                              com.austin.trading.service.CandidateForwardReturnBackfillService returnBackfillService) {
        this.service = service;
        this.returnBackfillService = returnBackfillService;
    }
    @GetMapping("/summary") public Map<String, Object> summary() { return service.summary(); }
    @GetMapping("/by-decision") public List<Map<String, Object>> byDecision() { return service.byDecision(); }
    @GetMapping("/by-grade") public List<Map<String, Object>> byGrade() { return service.byGrade(); }
    @GetMapping("/by-strategy") public List<Map<String, Object>> byStrategy() { return service.byStrategy(); }
    @GetMapping("/by-gate") public List<Map<String, Object>> byGate() { return service.byGate(); }
    @PostMapping("/backfill-from-paper")
    public Map<String, Object> backfillFromPaper(@RequestParam(defaultValue = "30") int days) {
        return service.backfillFromPaperTrades(days);
    }
    @PostMapping("/backfill-returns")
    public Map<String, Object> backfillReturns(@RequestParam(defaultValue = "60") int days) {
        return returnBackfillService.backfillReturns(days);
    }
}
