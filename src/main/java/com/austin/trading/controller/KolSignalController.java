package com.austin.trading.controller;

import com.austin.trading.dto.request.KolSignalCreateRequest;
import com.austin.trading.dto.request.KolStructuredResultRequest;
import com.austin.trading.dto.response.*;
import com.austin.trading.repository.KolSignalTraceRepository;
import com.austin.trading.repository.KolThemeSignalRepository;
import com.austin.trading.service.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/kol-signals")
public class KolSignalController {

    private final KolSignalIngestionService ingestionService;
    private final KolSignalExtractionService extractionService;
    private final KolSignalAggregationService aggregationService;
    private final KolSignalContextService contextService;
    private final KolSignalShadowModeService shadowModeService;
    private final KolThemeSignalRepository signalRepo;
    private final KolSignalTraceRepository traceRepo;

    public KolSignalController(KolSignalIngestionService ingestionService,
                               KolSignalExtractionService extractionService,
                               KolSignalAggregationService aggregationService,
                               KolSignalContextService contextService,
                               KolSignalShadowModeService shadowModeService,
                               KolThemeSignalRepository signalRepo,
                               KolSignalTraceRepository traceRepo) {
        this.ingestionService = ingestionService;
        this.extractionService = extractionService;
        this.aggregationService = aggregationService;
        this.contextService = contextService;
        this.shadowModeService = shadowModeService;
        this.signalRepo = signalRepo;
        this.traceRepo = traceRepo;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody KolSignalCreateRequest request) {
        try {
            return ResponseEntity.ok(ingestionService.create(request));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/structured-result")
    public ResponseEntity<?> structuredResult(@PathVariable Long id,
                                              @RequestBody KolStructuredResultRequest request) {
        try {
            var saved = extractionService.applyStructuredResult(id, request);
            return ResponseEntity.ok(Map.of(
                    "id", saved.getId(),
                    "signalStatus", saved.getSignalStatus(),
                    "weakSignalOnly", true
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/trace")
    public List<KolSignalTraceResponse> trace(@PathVariable Long id) {
        return traceRepo.findBySignalIdOrderByCreatedAtAsc(id).stream()
                .map(e -> new KolSignalTraceResponse(e.getId(), e.getSignalId(), e.getTraceStage(),
                        e.getTraceAction(), e.getDetailJson(), e.getCreatedAt()))
                .toList();
    }

    @GetMapping
    public List<KolSignalResponse> signals(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate target = date != null ? date : LocalDate.now();
        return signalRepo.findByTradingDateOrderByCreatedAtDesc(target).stream()
                .map(e -> ingestionService.toResponse(e, false, false))
                .toList();
    }

    @PostMapping("/aggregate")
    public List<KolThemeSnapshotResponse> aggregate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return aggregationService.rebuildDailySnapshot(date);
    }

    @GetMapping("/dashboard")
    public KolDashboardResponse dashboard(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate target = date != null ? date : LocalDate.now();
        var themes = aggregationService.getSnapshots(target);
        int signalCount = signalRepo.findByTradingDateOrderByCreatedAtDesc(target).size();
        LocalDate latestSignalDate = signalRepo.findLatestTradingDate();
        var freshness = new DataFreshnessService().evaluate(latestSignalDate, false);
        long signalCount7d = signalRepo.countByTradingDateBetween(target.minusDays(6), target);
        String warning = signalCount == 0 ? "NO_RECENT_SIGNAL" : freshness.warning();
        return new KolDashboardResponse(target, signalCount, themes.size(), themes,
                latestSignalDate, freshness.staleDays(), freshness.dataFreshnessStatus().name(),
                latestSignalDate, signalCount, signalCount7d, warning);
    }

    @GetMapping("/themes")
    public List<KolThemeSnapshotResponse> themes(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return aggregationService.getSnapshots(date != null ? date : LocalDate.now());
    }

    @GetMapping("/themes/{themeTag}")
    public List<KolThemeSnapshotResponse> theme(
            @PathVariable String themeTag,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return aggregationService.getTheme(date != null ? date : LocalDate.now(), themeTag);
    }

    @GetMapping("/context")
    public KolThemeContextResponse context(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return contextService.getContext(date != null ? date : LocalDate.now());
    }

    @PostMapping("/shadow/run")
    public KolShadowReportResponse shadowRun(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return shadowModeService.run(date);
    }

    @GetMapping("/shadow/report")
    public KolShadowReportResponse shadowReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return shadowModeService.report(date);
    }
}
