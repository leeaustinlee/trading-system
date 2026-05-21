package com.austin.trading.controller;

import com.austin.trading.dto.request.KolSignalCreateRequest;
import com.austin.trading.dto.response.NarrativeDashboardResponse;
import com.austin.trading.service.KolSignalIngestionService;
import com.austin.trading.service.NarrativeDashboardService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/narrative")
public class NarrativeController {

    private final KolSignalIngestionService ingestionService;
    private final NarrativeDashboardService dashboardService;

    public NarrativeController(KolSignalIngestionService ingestionService,
                               NarrativeDashboardService dashboardService) {
        this.ingestionService = ingestionService;
        this.dashboardService = dashboardService;
    }

    @PostMapping("/transcripts")
    public ResponseEntity<?> submitTranscript(@RequestBody KolSignalCreateRequest request) {
        try {
            return ResponseEntity.ok(ingestionService.create(request));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/dashboard")
    public NarrativeDashboardResponse dashboard(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return dashboardService.dashboard(date != null ? date : LocalDate.now());
    }
}
