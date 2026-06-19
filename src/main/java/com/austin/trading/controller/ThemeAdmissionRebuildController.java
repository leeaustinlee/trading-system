package com.austin.trading.controller;

import com.austin.trading.dto.internal.ThemeAdmissionWriteSummary;
import com.austin.trading.service.ThemeAdmissionRebuildService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Manual P1-A theme admission rebuild. Shadow by default; guarded production pool write only. */
@RestController
@RequestMapping("/api/theme-admission")
public class ThemeAdmissionRebuildController {

    private final ThemeAdmissionRebuildService rebuildService;

    public ThemeAdmissionRebuildController(ThemeAdmissionRebuildService rebuildService) {
        this.rebuildService = rebuildService;
    }

    @PostMapping("/rebuild")
    public Map<String, Object> rebuild(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "60") int days,
            @RequestParam(defaultValue = "false") boolean write) {
        LocalDate end = endDate == null ? LocalDate.now() : endDate;
        LocalDate start = startDate == null ? end.minusDays(Math.max(days, 1) - 1L) : startDate;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("startDate must be on or before endDate");
        }
        ThemeAdmissionRebuildService.Result result = rebuildService.rebuild(start, end, write);
        ThemeAdmissionWriteSummary s = result.writeSummary();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("startDate", result.startDate());
        response.put("endDate", result.endDate());
        response.put("processedDays", result.processedDays());
        response.put("shadowRows", result.shadowRows());
        response.put("processedSignals", s.processedSignals());
        response.put("admittedCandidates", s.admittedCandidates());
        response.put("admittedWatchlists", s.admittedWatchlists());
        response.put("skippedLimitRisk", s.skippedLimitRisk());
        response.put("skippedAlreadyExists", s.skippedAlreadyExists());
        response.put("rejectedBadData", s.rejectedBadData());
        response.put("rejectedLiquidity", s.rejectedLiquidity());
        response.put("rejectedWeakTheme", s.rejectedWeakTheme());
        response.put("shadowOnly", result.shadowOnly());
        response.put("productionBuyImpact", false);
        return response;
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(RuntimeException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.getMessage());
        body.put("productionBuyImpact", false);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
