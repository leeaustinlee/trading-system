package com.austin.trading.controller;

import com.austin.trading.service.CandidateForwardTrackingService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/forward-tracking")
public class CandidateForwardTrackingController {
    private final CandidateForwardTrackingService service;
    public CandidateForwardTrackingController(CandidateForwardTrackingService service) { this.service = service; }
    @GetMapping("/summary") public Map<String, Object> summary() { return service.summary(); }
    @GetMapping("/by-decision") public List<Map<String, Object>> byDecision() { return service.byDecision(); }
    @GetMapping("/by-grade") public List<Map<String, Object>> byGrade() { return service.byGrade(); }
    @GetMapping("/by-strategy") public List<Map<String, Object>> byStrategy() { return service.byStrategy(); }
    @GetMapping("/by-gate") public List<Map<String, Object>> byGate() { return service.byGate(); }
}
