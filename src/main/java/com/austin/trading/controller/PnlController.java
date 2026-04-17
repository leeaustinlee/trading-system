package com.austin.trading.controller;

import com.austin.trading.dto.request.DailyPnlCreateRequest;
import com.austin.trading.dto.response.DailyPnlResponse;
import com.austin.trading.dto.response.PnlSummaryResponse;
import com.austin.trading.service.PnlService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/pnl")
public class PnlController {

    private final PnlService pnlService;

    public PnlController(PnlService pnlService) {
        this.pnlService = pnlService;
    }

    @GetMapping("/daily")
    public ResponseEntity<DailyPnlResponse> getDaily() {
        return pnlService.getLatestDaily()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/summary")
    public PnlSummaryResponse getSummary(@RequestParam(defaultValue = "20") int days) {
        return pnlService.getSummary(days);
    }

    /**
     * GET /api/pnl/history
     * ?dateFrom=2026-04-01 &dateTo=2026-04-17 &page=0 &size=200 &limit=200
     * dateFrom/dateTo 有值時使用日期區間查詢；否則使用 limit 倒序查詢。
     */
    @GetMapping("/history")
    public List<DailyPnlResponse> getHistory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "200") int limit
    ) {
        if (dateFrom != null || dateTo != null) {
            return pnlService.getDailyHistoryByRange(dateFrom, dateTo, page, limit);
        }
        return pnlService.getDailyHistory(limit);
    }

    @PostMapping("/daily")
    public DailyPnlResponse createDaily(@Valid @RequestBody DailyPnlCreateRequest request) {
        return pnlService.create(request);
    }
}
